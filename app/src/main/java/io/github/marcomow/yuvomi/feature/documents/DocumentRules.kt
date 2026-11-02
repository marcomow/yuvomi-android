package io.github.marcomow.yuvomi.feature.documents

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The pure rules behind the documents list, ported from upstream without DOM or page state:
 * `public/utils/document-facets.js` (search, facet counts, sorting, folder chip),
 * `folder-tree.js` (subtree, path, move rules, flattened tree), `date-status.js` and
 * `document-expiry.js` (expiry chip and viewer line), `document-preview.js` (`previewKind`),
 * `document-thumbs.js` (`documentStorageBackend`, `documentThumbKind`) and the page's own
 * helpers in `public/pages/documents.js` (`matchesFolder`, `formatFileSize`, `mayManage`).
 */
object DocumentRules {
    const val STATUS_ACTIVE = "active"
    const val STATUS_ARCHIVED = "archived"
    const val OTHER = "other"
    const val VISIBILITY_FAMILY = "family"
    const val VISIBILITY_RESTRICTED = "restricted"
    const val VISIBILITY_PRIVATE = "private"

    /** `CATEGORIES` of the page and the route, in their order. */
    val CATEGORIES = listOf(
        "medical", "school", "identity", "insurance", "finance", "home", "vehicle",
        "legal", "travel", "pets", "warranty", "taxes", "work", "other",
    )
    val VISIBILITIES = listOf(VISIBILITY_FAMILY, VISIBILITY_RESTRICTED, VISIBILITY_PRIVATE)

    /** `DOCUMENT_SORTS`; `updated` mirrors the server's order and is the default. */
    val SORTS = listOf("updated", "name", "size", "expiring")

    /** The folder filter's two fixed rows: every document, and documents in no folder. */
    const val FOLDER_ALL = ""
    const val FOLDER_NONE = "__none"

    /** `MAX_FOLDER_DEPTH` of `folder-tree.js`, the limit the server names in `documents_folder_too_deep`. */
    const val MAX_FOLDER_DEPTH = 5
    private const val MAX_HOPS = 64

    /** `DATE_STATUS_ALERT_DAYS`. */
    const val ALERT_DAYS = 30

    /** `THUMB_MAX_BYTES`: larger files get no list preview. */
    const val THUMB_MAX_BYTES = 10L * 1024 * 1024

    /** `maxUploadBytes()` before `/meta/options` answered. */
    const val DEFAULT_MAX_UPLOAD_BYTES = 5L * 1024 * 1024

    const val REMINDER_MIN_DAYS = 0
    const val REMINDER_MAX_DAYS = 365

    // ── Search and facets (document-facets.js) ──

    fun normalizeQuery(value: String?): String = value.orEmpty().trim().lowercase(Locale.ROOT)

    /** Name, description and file name. [query] is already normalized. */
    fun matchesQuery(doc: Document, query: String): Boolean {
        if (query.isEmpty()) return true
        return doc.name.lowercase(Locale.ROOT).contains(query) ||
            doc.description.orEmpty().lowercase(Locale.ROOT).contains(query) ||
            doc.originalName.orEmpty().lowercase(Locale.ROOT).contains(query)
    }

    /** What the list is looking at; everything but [status] is a client facet over the loaded list. */
    data class Filter(
        val status: String = STATUS_ACTIVE,
        val category: String = "",
        val folderId: String = FOLDER_ALL,
        val expiringSoon: Boolean = false,
        val query: String = "",
    ) {
        val hasFacet: Boolean get() = category.isNotEmpty() || folderId.isNotEmpty() || expiringSoon
        val normalizedQuery: String get() = normalizeQuery(query)
        val selectedFolder: Int? get() = folderId.toIntOrNull()?.takeIf { it > 0 }
    }

    fun matchesCategory(doc: Document, filter: Filter): Boolean = filter.category.isEmpty() || doc.category == filter.category

    /** `matchesFolder`: a folder shows its whole subtree, like the server (#785). */
    fun matchesFolder(doc: Document, filter: Filter, folders: List<DocumentFolder>): Boolean = when (filter.folderId) {
        FOLDER_ALL -> true
        FOLDER_NONE -> doc.folderId == null
        else -> {
            val id = filter.folderId.toIntOrNull()
            id != null && doc.folderId != null && doc.folderId in subtreeIds(folders, id)
        }
    }

    fun isExpiringOrOverdue(doc: Document, today: LocalDate): Boolean =
        dateStatus(doc.expiresAt, today)?.let { it.state != DateState.VALID } ?: false

    private fun matchesExpiring(doc: Document, filter: Filter, today: LocalDate) = !filter.expiringSoon || isExpiringOrOverdue(doc, today)

    /** `filteredDocuments` after `applyFilters`: facets, search, then the chosen order. */
    fun visible(
        docs: List<Document>,
        folders: List<DocumentFolder>,
        filter: Filter,
        sort: String,
        direction: String,
        today: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): List<Document> {
        val query = filter.normalizedQuery
        val kept = docs.filter {
            matchesCategory(it, filter) && matchesFolder(it, filter, folders) && matchesExpiring(it, filter, today) && matchesQuery(it, query)
        }
        return sort(kept, sort, direction, locale)
    }

    /** `categoryFacetCounts` under the folder axis and the search; `""` counts all. */
    fun categoryCounts(docs: List<Document>, folders: List<DocumentFolder>, filter: Filter, withQuery: Boolean = true): Map<String, Int> {
        val query = if (withQuery) filter.normalizedQuery else ""
        val scope = docs.filter { matchesFolder(it, filter, folders) && matchesQuery(it, query) }
        return buildMap {
            put("", scope.size)
            scope.groupingBy { it.category }.eachCount().forEach { (k, v) -> put(k, v) }
        }
    }

    /** The category chips: those with documents in the folder (not the search), plus the chosen one. */
    fun presentCategories(docs: List<Document>, folders: List<DocumentFolder>, filter: Filter): List<String> {
        val present = categoryCounts(docs, folders, filter, withQuery = false)
        return CATEGORIES.filter { (present[it] ?: 0) > 0 || it == filter.category }
    }

    /** `folderFacetCounts` under the category axis and the search; a folder counts its subtree. */
    fun folderCounts(docs: List<Document>, folders: List<DocumentFolder>, filter: Filter): Map<String, Int> {
        val query = filter.normalizedQuery
        val scope = docs.filter { matchesCategory(it, filter) && matchesQuery(it, query) }
        val own = scope.mapNotNull { it.folderId }.groupingBy { it }.eachCount()
        return buildMap {
            put(FOLDER_ALL, scope.size)
            put(FOLDER_NONE, scope.count { it.folderId == null })
            folders.forEach { folder -> put(folder.id.toString(), subtreeIds(folders, folder.id).sumOf { own[it] ?: 0 }) }
        }
    }

    /** `expiringCount`, and whether the deadline chip shows at all (`renderExpiringChip`). */
    fun expiringCount(docs: List<Document>, folders: List<DocumentFolder>, filter: Filter, today: LocalDate): Int {
        val query = filter.normalizedQuery
        return docs.count { matchesCategory(it, filter) && matchesFolder(it, filter, folders) && matchesQuery(it, query) && isExpiringOrOverdue(it, today) }
    }

    fun showsExpiringChip(docs: List<Document>, folders: List<DocumentFolder>, filter: Filter, today: LocalDate): Boolean =
        filter.expiringSoon || docs.any { matchesCategory(it, filter) && matchesFolder(it, filter, folders) && isExpiringOrOverdue(it, today) }

    // ── Sorting ──

    fun defaultDirection(sort: String): String = if (sort == "updated" || sort == "size") "desc" else "asc"

    /** `sortDocuments`: documents without an expiry stay last in both directions. */
    fun sort(docs: List<Document>, sort: String, direction: String?, locale: Locale = Locale.getDefault()): List<Document> {
        val key = sort.takeIf { it in SORTS } ?: "updated"
        val sign = if ((direction ?: defaultDirection(key)) == "desc") -1 else 1
        val collator = java.text.Collator.getInstance(locale)
        val ascending: Comparator<Document> = when (key) {
            "name" -> Comparator { a, b -> collator.compare(a.name, b.name) }
            "size" -> compareBy { it.fileSize ?: 0L }
            "expiring" -> Comparator { a, b -> a.expiresAt!!.compareTo(b.expiresAt!!) }
            else -> compareBy { it.updatedAt.orEmpty() }
        }
        return docs.sortedWith { a, b ->
            if (key == "expiring" && (a.expiresAt.isNullOrEmpty() || b.expiresAt.isNullOrEmpty())) {
                when {
                    a.expiresAt.isNullOrEmpty() && b.expiresAt.isNullOrEmpty() -> 0
                    a.expiresAt.isNullOrEmpty() -> 1
                    else -> -1
                }
            } else {
                sign * ascending.compare(a, b)
            }
        }
    }

    // ── Folder tree (folder-tree.js) ──

    /** This folder and everything below it; always contains [folderId]. */
    fun subtreeIds(folders: List<DocumentFolder>, folderId: Int): Set<Int> {
        val children = folders.filter { it.parentId != null }.groupBy { it.parentId!! }
        val seen = linkedSetOf(folderId)
        val queue = ArrayDeque(listOf(folderId))
        while (queue.isNotEmpty()) {
            for (child in children[queue.removeFirst()].orEmpty()) {
                if (seen.add(child.id)) queue.addLast(child.id)
            }
        }
        return seen
    }

    /** Root first; empty when the folder does not exist. */
    fun folderPath(folders: List<DocumentFolder>, folderId: Int): List<DocumentFolder> {
        val byId = folders.associateBy { it.id }
        val chain = ArrayList<DocumentFolder>()
        var current: Int? = folderId
        var hops = 0
        while (current != null && byId.containsKey(current) && hops < MAX_HOPS) {
            val folder = byId.getValue(current)
            chain.add(0, folder)
            current = folder.parentId
            hops++
        }
        return chain
    }

    fun subtreeHeight(folders: List<DocumentFolder>, folderId: Int): Int {
        val children = folders.filter { it.parentId != null }.groupBy { it.parentId!! }
        var height = 0
        var level = listOf(folderId)
        val seen = hashSetOf(folderId)
        while (level.isNotEmpty() && height < MAX_HOPS) {
            height++
            level = level.flatMap { id -> children[id].orEmpty().filter { seen.add(it.id) }.map { it.id } }
        }
        return maxOf(height, 1)
    }

    enum class MoveIssue { SELF, DESCENDANT, MISSING_PARENT, TOO_DEEP }

    /** `folderMoveIssue`: may [folderId] (null: a new folder) hang under [parentId] (null: top level)? */
    fun moveIssue(folders: List<DocumentFolder>, folderId: Int?, parentId: Int?): MoveIssue? {
        if (parentId == null) return null
        if (folderId != null && parentId == folderId) return MoveIssue.SELF
        if (folders.none { it.id == parentId }) return MoveIssue.MISSING_PARENT
        val path = folderPath(folders, parentId)
        if (folderId != null && path.any { it.id == folderId }) return MoveIssue.DESCENDANT
        val height = if (folderId == null) 1 else subtreeHeight(folders, folderId)
        return if (path.size + height > MAX_FOLDER_DEPTH) MoveIssue.TOO_DEEP else null
    }

    data class FolderRow(val folder: DocumentFolder, val depth: Int, val hasChildren: Boolean)

    /**
     * `flattenFolderTree`: display order with depth. [expanded] null opens every branch (the
     * pickers); a folder whose parent is missing becomes a root instead of disappearing.
     */
    fun flatten(folders: List<DocumentFolder>, expanded: Set<Int>? = null): List<FolderRow> {
        val ids = folders.map { it.id }.toSet()
        val children = folders.filter { it.parentId != null && it.parentId in ids && it.parentId != it.id }.groupBy { it.parentId!! }
        val roots = folders.filter { it.parentId == null || it.parentId !in ids || it.parentId == it.id }
        val rows = ArrayList<FolderRow>()
        fun walk(nodes: List<DocumentFolder>, depth: Int) {
            if (depth > MAX_HOPS) return
            for (node in nodes) {
                val kids = children[node.id].orEmpty()
                rows += FolderRow(node, depth, kids.isNotEmpty())
                if (expanded == null || node.id in expanded) walk(kids, depth + 1)
            }
        }
        walk(roots, 0)
        return rows
    }

    /** `moveFolder`: the possible parents, without the folder and its own subtree. */
    fun folderMoveTargets(folders: List<DocumentFolder>, folderId: Int): List<FolderRow> {
        val forbidden = subtreeIds(folders, folderId)
        return flatten(folders).filter { it.folder.id !in forbidden }
    }

    // ── Expiry (date-status.js, document-expiry.js) ──

    enum class DateState { VALID, EXPIRING, EXPIRED }

    data class DateStatus(val state: DateState, val endDate: LocalDate, val days: Long)

    fun dateStatus(dateKey: String?, today: LocalDate): DateStatus? {
        val date = dateKey?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() } ?: return null
        val days = ChronoUnit.DAYS.between(today, date)
        val state = when {
            days < 0 -> DateState.EXPIRED
            days <= ALERT_DAYS -> DateState.EXPIRING
            else -> DateState.VALID
        }
        return DateStatus(state, date, days)
    }

    data class ExpiryChip(val expired: Boolean, val key: String, val shortKey: String, val count: Long)

    /** `expiryChipSpec`: only "soon" or "expired" earn a chip on the row. */
    fun expiryChip(status: DateStatus?): ExpiryChip? = when (status?.state) {
        null, DateState.VALID -> null
        DateState.EXPIRED -> ExpiryChip(true, "documents.expiredDays", "documents.expiredShort", -status.days)
        DateState.EXPIRING -> ExpiryChip(false, "documents.expiringInDays", "documents.expiringShort", status.days)
    }

    /** `expiryViewerSpec`: the viewer always names a set date. */
    fun expiryViewerKey(status: DateStatus): String = when (status.state) {
        DateState.EXPIRED -> "documents.expiryEndedOn"
        DateState.EXPIRING -> "documents.expiryEndsOn"
        DateState.VALID -> "documents.expiryValidUntil"
    }

    // ── Row details ──

    private const val PLURAL_SUFFIX_MAX = 3
    private const val PLURAL_STEM_MIN = 3

    /** `folderRepeatsCategory`: "School · School" or "Insurance · Insurances" say it once. */
    fun folderRepeatsCategory(folderName: String?, categoryLabel: String?): Boolean {
        val folder = folderName.orEmpty().trim().lowercase()
        val category = categoryLabel.orEmpty().trim().lowercase()
        if (folder.isEmpty() || category.isEmpty()) return false
        if (folder == category) return true
        val (shorter, longer) = if (folder.length < category.length) folder to category else category to folder
        return shorter.length >= PLURAL_STEM_MIN && longer.length - shorter.length <= PLURAL_SUFFIX_MAX && longer.startsWith(shorter)
    }

    /** `showsFolderChip`: not when it repeats the category, and not inside that very folder. */
    fun showsFolderChip(doc: Document, categoryLabel: String, selectedFolderId: String): Boolean {
        if (doc.folderName.isNullOrEmpty() || doc.folderId == null) return false
        if (doc.folderId.toString() == selectedFolderId) return false
        return !folderRepeatsCategory(doc.folderName, categoryLabel)
    }

    /** `showsVisibility`: only a visibility other than the default is named on a row. */
    fun showsVisibility(visibility: String?): Boolean = (visibility ?: VISIBILITY_FAMILY) != VISIBILITY_FAMILY

    enum class PreviewKind { PDF, IMAGE, TEXT }

    /** `previewKind`: the six inline types the server's `PREVIEWABLE_MIME` serves. */
    fun previewKind(mime: String?): PreviewKind? = when (mime.orEmpty().substringBefore(';').trim().lowercase()) {
        "application/pdf" -> PreviewKind.PDF
        "image/png", "image/jpeg", "image/webp" -> PreviewKind.IMAGE
        "text/plain", "text/csv" -> PreviewKind.TEXT
        else -> null
    }

    /** `documentStorageBackend`: older rows have only `storage_provider`. */
    fun storageBackend(doc: Document): String = doc.storageBackend?.takeIf { it.isNotEmpty() }
        ?: if (doc.storageProvider == "external") "dms" else "local"

    /** `documentThumbKind`: local images and PDFs up to 10 MB get a list preview. */
    fun thumbKind(doc: Document): PreviewKind? {
        if (storageBackend(doc) != "local") return null
        val kind = previewKind(doc.mimeType)
        if (kind != PreviewKind.IMAGE && kind != PreviewKind.PDF) return null
        val size = doc.fileSize ?: return null
        return if (size in 1..THUMB_MAX_BYTES) kind else null
    }

    /** `docSupportsThumbnail`: a Paperless-linked document has the DMS's own thumbnail. */
    fun hasDmsThumbnail(doc: Document): Boolean = storageBackend(doc) == "dms" && doc.dmsAccountId != null && doc.dmsProvider == "paperless"

    /** Where a file lives, when it is worth a badge (`storageBadgeHtml`): an i18n key or null. */
    fun storageBadgeKey(doc: Document): String? = when (storageBackend(doc)) {
        "webdav" -> "documents.storageWebdav"
        "google_drive" -> "documents.storageGoogleDrive"
        "dms" -> if (doc.dmsAccountId == null) "documents.storageDmsUnavailable" else "documents.storageDms"
        "local" -> if (!doc.storageKey.isNullOrEmpty()) "documents.storageLocalFolder" else null
        else -> null
    }

    /** `externalUrl` of the viewer: only an http(s) deep link of a DMS document. */
    fun dmsLink(doc: Document): String? =
        doc.externalUrl?.takeIf { storageBackend(doc) == "dms" && Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }

    /** `formatFileSize`: unknown is "-", not "0 KB". */
    fun fileSize(bytes: Long?): String = when {
        bytes == null -> "-"
        bytes == 0L -> "0 KB"
        bytes < 1024 * 1024 -> "${maxOf(1L, Math.round(bytes / 1024.0))} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun megabytes(bytes: Long): Long = Math.round(bytes / (1024.0 * 1024.0))

    /** The name a file brings when the name field is empty: the file name without its extension. */
    fun nameFromFile(fileName: String): String = fileName.replace(Regex("\\.[^.]+$"), "")

    /**
     * `mayManage`: write access to the module, and the document is the member's own, or the
     * member is an admin (`canManageDocument`, server/services/document-access.js).
     */
    fun mayManage(doc: Document, canWrite: Boolean, userId: Int, isAdmin: Boolean): Boolean =
        canWrite && (isAdmin || doc.createdBy == userId)

    /** `otherStatus`: the view an empty search can also look in. */
    fun otherStatus(status: String) = if (status == STATUS_ACTIVE) STATUS_ARCHIVED else STATUS_ACTIVE
}
