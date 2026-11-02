package io.github.marcomow.yuvomi.feature.inventory

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The pure ports of `public/pages/inventory.js`, `public/utils/inventory-warranty.js` and
 * `date-status.js` (docs/spec/inventory.md): deadlines, chips, groups, metrics, labels, the
 * form's payload and the error sentences.
 */
object InventoryRules {
    val CONDITIONS = listOf("new", "good", "fair", "poor")
    val STATUSES = listOf("active", "sold", "disposed", "lost")
    val ROLES = listOf("purchase", "refund", "instalment", "maintenance", "accessory")
    val DOCUMENT_CATEGORIES = listOf("medical", "school", "identity", "insurance", "finance", "home", "vehicle", "legal", "travel", "pets", "warranty", "taxes", "work", "other")
    const val MAX_TRACKED_DATES = 10
    const val ALERT_DAYS = 30
    const val WARRANTY_EXPIRED_ATTENTION_DAYS = 30
    const val UNLOCATED = "__unlocated__"

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    fun statusLabel(status: String, strings: Strings) = strings.t("inventory.status${cap(status)}")
    fun conditionLabel(condition: String, strings: Strings) = strings.t("inventory.condition${cap(condition)}")
    fun roleLabel(role: String, strings: Strings) = strings.t("inventory.role${cap(role)}")
    fun odometerUnitLabel(unit: String?, strings: Strings) = strings.t(if (unit == "mi") "inventory.odometerUnitMi" else "inventory.odometerUnitKm")

    /** `categoryLabel`. */
    fun categoryLabel(c: InventoryCategory, strings: Strings): String = c.labelKey?.let { strings.t(it) } ?: c.name ?: c.key

    /** `itemCategoryLabel`: the item carries its category's name and key. */
    fun itemCategoryLabel(item: InventoryItem, strings: Strings): String = item.categoryLabelKey?.let { strings.t(it) } ?: item.categoryName ?: item.category

    fun tracksOdometer(key: String, categories: List<InventoryCategory>): Boolean = categories.firstOrNull { it.key == key }?.tracksOdometer == true

    // ── Deadlines (inventory-warranty.js, date-status.js) ──

    enum class State { EXPIRED, EXPIRING, VALID }

    data class DateStatus(val state: State, val endDate: LocalDate, val days: Long)

    fun parse(key: String?): LocalDate? = key?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    /** `dateStatus`: expired before today, expiring within 30 days, else valid. */
    fun dateStatus(key: String?, today: LocalDate): DateStatus? {
        val d = parse(key) ?: return null
        val days = ChronoUnit.DAYS.between(today, d)
        return DateStatus(if (days < 0) State.EXPIRED else if (days <= ALERT_DAYS) State.EXPIRING else State.VALID, d, days)
    }

    /** `warrantyEndDateKey`: the purchase day N months on, clamped to the month's last day. */
    fun warrantyEnd(purchaseDate: String?, months: Int?): LocalDate? {
        val d = parse(purchaseDate) ?: return null
        if (months == null) return null
        return d.withDayOfMonth(1).plusMonths(months.toLong()).let { m -> m.withDayOfMonth(minOf(d.dayOfMonth, m.lengthOfMonth())) }
    }

    fun warrantyStatus(purchaseDate: String?, months: Int?, today: LocalDate): DateStatus? =
        warrantyEnd(purchaseDate, months)?.let { dateStatus(it.toString(), today) }

    data class Deadline(val kind: String, val label: String?, val state: State, val endDate: LocalDate, val days: Long)

    /** `deadlineChipSpec`: the most urgent of an overdue date, an expiring one, a warranty just ended. */
    fun deadline(item: InventoryItem, today: LocalDate): Deadline? {
        data class C(val rank: Int, val d: Deadline)
        val candidates = mutableListOf<C>()
        warrantyStatus(item.purchaseDate, item.warrantyMonths, today)?.let { w ->
            if (w.state == State.EXPIRING || (w.state == State.EXPIRED && -w.days <= WARRANTY_EXPIRED_ATTENTION_DAYS)) {
                candidates += C(if (w.state == State.EXPIRED) 2 else 1, Deadline("warranty", null, w.state, w.endDate, w.days))
            }
        }
        for (t in item.trackedDates) {
            val s = dateStatus(t.date, today) ?: continue
            if (s.state == State.VALID) continue
            candidates += C(if (s.state == State.EXPIRED) 0 else 1, Deadline("tracked", t.label, s.state, s.endDate, s.days))
        }
        return candidates.sortedWith(compareBy<C> { it.rank }.thenBy { it.d.days }).firstOrNull()?.d
    }

    fun countDeadlines(items: List<InventoryItem>, today: LocalDate) = items.count { deadline(it, today) != null }

    /** The chip's text (`deadlineChipHtml`); [date] formats the end date. */
    fun deadlineText(d: Deadline, date: (LocalDate) -> String, strings: Strings): String = when {
        d.kind == "warranty" -> if (d.state == State.EXPIRED) strings.t("inventory.warrantyChipExpired") else strings.t("inventory.warrantyChipUntil", "date" to date(d.endDate))
        d.state == State.EXPIRED -> strings.t("inventory.deadlineChipOverdue", "label" to d.label)
        d.days == 0L -> strings.t("inventory.deadlineChipToday", "label" to d.label)
        else -> strings.t("inventory.deadlineChipInDays", "label" to d.label, "count" to d.days)
    }

    /** `warrantyDetailValue`. */
    fun warrantyText(item: InventoryItem, today: LocalDate, date: (LocalDate) -> String, strings: Strings): String {
        val months = item.warrantyMonths ?: return ""
        val base = strings.t("inventory.warrantyMonthsValue", "count" to months)
        val s = warrantyStatus(item.purchaseDate, months, today) ?: return base
        return "$base · ${warrantyStatusText(s, date, strings)}"
    }

    fun warrantyStatusText(s: DateStatus, date: (LocalDate) -> String, strings: Strings): String = when (s.state) {
        State.EXPIRED -> strings.t("inventory.warrantyStatusExpired", "date" to date(s.endDate))
        State.EXPIRING -> strings.t("inventory.warrantyStatusExpiringSoon", "count" to s.days)
        State.VALID -> strings.t("inventory.warrantyStatusValid", "date" to date(s.endDate))
    }

    /** The countdown of a tracked date (`updateTrackedDateRowCountdown`). */
    fun countdown(key: String?, today: LocalDate, strings: Strings): String {
        val s = dateStatus(key, today) ?: return ""
        return when {
            s.days < 0 -> strings.t("inventory.trackedDateOverdueDays", "count" to -s.days)
            s.days == 0L -> strings.t("inventory.trackedDateDueToday")
            else -> strings.t("inventory.trackedDateInDays", "count" to s.days)
        }
    }

    /** A tracked date is "due" (and offers "Done") unless it is more than 30 days away. */
    fun isDue(t: TrackedDate, today: LocalDate): Boolean = dateStatus(t.date, today)?.let { it.state != State.VALID } == true

    // ── List (visibleGroups, categoryChips, computeMetrics) ──

    /** `matchesQuery`: name, brand, model, serial number. */
    fun matches(item: InventoryItem, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return listOf(item.name, item.brand, item.model, item.serialNumber).any { it?.lowercase()?.contains(q) == true }
    }

    data class Group(val key: String, val name: String, val items: List<InventoryItem>)

    /** `groupItemsByCategory`: the categories' order, unknown keys after. */
    fun groupByCategory(items: List<InventoryItem>, categories: List<InventoryCategory>, strings: Strings): List<Group> {
        val grouped = LinkedHashMap<String, MutableList<InventoryItem>>()
        items.forEach { grouped.getOrPut(it.category) { mutableListOf() } += it }
        val ordered = categories.map { it.key }
        val keys = ordered.filter { it in grouped } + grouped.keys.filter { it !in ordered }
        return keys.map { k -> Group(k, itemCategoryLabel(grouped.getValue(k).first(), strings), grouped.getValue(k)) }
    }

    /** `groupItemsByLocation`: by top-level location in order, then "No location". */
    fun groupByLocation(items: List<InventoryItem>, locations: List<InventoryLocation>, strings: Strings): List<Group> {
        val root = HashMap<Int, InventoryLocation>()
        locations.forEach { r -> root[r.id] = r; r.subcategories.forEach { root[it.id] = r } }
        val grouped = LinkedHashMap<String, MutableList<InventoryItem>>()
        items.forEach { item ->
            val key = item.locationId?.let { root[it] }?.id?.toString() ?: UNLOCATED
            grouped.getOrPut(key) { mutableListOf() } += item
        }
        val out = locations.map { it.id.toString() }.filter { it in grouped }.map { k -> Group(k, locations.first { it.id.toString() == k }.name, grouped.getValue(k)) }
        return out + listOfNotNull(grouped[UNLOCATED]?.let { Group(UNLOCATED, strings.t("inventory.unlocated"), it) })
    }

    /** `visibleGroups`: in a category the items group by location, otherwise by category. */
    fun visibleGroups(
        items: List<InventoryItem>,
        category: String?,
        query: String,
        attention: Boolean,
        today: LocalDate,
        categories: List<InventoryCategory>,
        locations: List<InventoryLocation>,
        strings: Strings,
    ): List<Group> {
        val scoped = if (category != null) items.filter { it.category == category } else items
        val filtered = scoped.filter { matches(it, query) && (!attention || deadline(it, today) != null) }
        if (filtered.isEmpty()) return emptyList()
        return if (category != null) groupByLocation(filtered, locations, strings) else groupByCategory(filtered, categories, strings)
    }

    /** `categoryChips`: one per category in use, with its count. */
    fun chips(items: List<InventoryItem>, categories: List<InventoryCategory>, strings: Strings): List<Group> = groupByCategory(items, categories, strings)

    /** `computeMetrics`' value: the sum of prices in the household's currency. */
    fun totalValue(items: List<InventoryItem>, currency: String): Double = items.filter { it.purchasePrice != null && it.currency == currency }.sumOf { it.purchasePrice!! }

    /** `locationOptionsHtml`: "No location", then each place and its sub-places as "Parent · Child". */
    fun locationOptions(locations: List<InventoryLocation>, strings: Strings): List<Pair<Int?, String>> = buildList {
        add(null to strings.t("inventory.unlocated"))
        locations.forEach { root ->
            add(root.id to root.name)
            root.subcategories.forEach { add(it.id to "${root.name} · ${it.name}") }
        }
    }

    // ── History (odometerChartPoints) ──

    data class Point(val date: LocalDate, val value: Long)

    fun odometerPoints(history: ItemHistory?, item: InventoryItem): List<Point> {
        val points = history?.timeline.orEmpty().filter { it.type == "service_log" && it.odometer != null }
            .mapNotNull { e -> parse(e.date)?.let { Point(it, e.odometer!!) } }.toMutableList()
        val on = parse(item.odometerOn)
        if (item.odometer != null && on != null && points.none { it.date == on }) points += Point(on, item.odometer)
        return points.sortedBy { it.date }
    }

    // ── Form (buildItemForm, saveItem, collectTrackedDates) ──

    /** The form's tracked-date rows, all as typed. */
    data class DateRow(
        val label: String = "",
        val date: String? = null,
        val offset: String = "30",
        val intervalMonths: String = "",
        val intervalDistance: String = "",
    )

    data class Form(
        val name: String = "",
        val category: String = "other",
        val locationId: Int? = null,
        val status: String = "active",
        val purchaseDate: String? = null,
        val price: String = "",
        val brand: String = "",
        val model: String = "",
        val serial: String = "",
        val vendor: String = "",
        val account: String = "",
        val warranty: String = "",
        val condition: String = "good",
        val notes: String = "",
        val dates: List<DateRow> = emptyList(),
        val photo: String? = null,
        val odometer: String = "",
        val odometerUnit: String = "km",
        val odometerOn: String? = null,
        val attachmentCategory: String = "warranty",
    )

    fun form(item: InventoryItem?, priceInput: (Double?, String?) -> String): Form {
        if (item == null) return Form()
        return Form(
            name = item.name,
            category = item.category,
            locationId = item.locationId,
            status = item.status,
            purchaseDate = item.purchaseDate,
            price = priceInput(item.purchasePrice, item.currency),
            brand = item.brand.orEmpty(),
            model = item.model.orEmpty(),
            serial = item.serialNumber.orEmpty(),
            vendor = item.vendor.orEmpty(),
            account = item.accountUsername.orEmpty(),
            warranty = item.warrantyMonths?.toString().orEmpty(),
            condition = item.condition,
            notes = item.notes.orEmpty(),
            dates = item.trackedDates.map { DateRow(it.label, it.date, (it.reminderOffsetDays ?: 30).toString(), it.intervalMonths?.toString().orEmpty(), it.intervalDistance?.toString().orEmpty()) },
            photo = item.photoData,
            odometer = item.odometer?.toString().orEmpty(),
            odometerUnit = item.odometerUnit ?: "km",
            odometerOn = item.odometerOn,
        )
    }

    /** `collectTrackedDates`: rows with a label and a date; a blank offset is 30. */
    fun trackedDates(rows: List<DateRow>): JsonArray = JsonArray(
        rows.map { it.copy(label = it.label.trim()) }.filter { it.label.isNotEmpty() && !it.date.isNullOrEmpty() }.map { r ->
            buildJsonObject {
                put("label", r.label)
                put("date", r.date)
                put("reminder_offset_days", r.offset.trim().toDoubleOrNull()?.let(::number) ?: 30)
                put("interval_months", r.intervalMonths.trim().toDoubleOrNull()?.let(::number))
                put("interval_distance", r.intervalDistance.trim().toDoubleOrNull()?.let(::number))
            }
        },
    )

    /** A whole number as an integer, anything else as typed, so the server's own check answers. */
    private fun number(d: Double): Number = if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < Long.MAX_VALUE) d.toLong() else d

    /**
     * `saveItem`'s payload. [price] is the parsed price, or null without the price capability
     * (then the field is not sent at all).
     */
    fun body(f: Form, tracksOdometer: Boolean, showsPrice: Boolean, price: Double?, attachmentIds: List<Int>?, entryId: Int?): JsonObject {
        val odometerRaw = if (tracksOdometer) f.odometer.trim() else ""
        fun orNull(v: String) = v.trim().ifEmpty { null }
        return buildJsonObject {
            put("name", f.name.trim())
            put("category", f.category)
            put("location_id", f.locationId)
            put("purchase_date", f.purchaseDate)
            if (showsPrice) put("purchase_price", price)
            put("status", f.status)
            put("brand", orNull(f.brand))
            put("model", orNull(f.model))
            put("serial_number", orNull(f.serial))
            put("vendor", orNull(f.vendor))
            put("account_username", orNull(f.account))
            put("warranty_months", f.warranty.trim().toDoubleOrNull()?.let(::number))
            put("condition", f.condition)
            put("notes", orNull(f.notes))
            put("tracked_dates", trackedDates(f.dates))
            put("photo_data", f.photo)
            put("odometer", odometerRaw.toDoubleOrNull()?.let(::number))
            put("odometer_unit", if (odometerRaw.isEmpty()) null else f.odometerUnit)
            put("odometer_on", if (tracksOdometer) f.odometerOn else null)
            if (attachmentIds != null) put("attachment_document_ids", JsonArray(attachmentIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            if (entryId != null) put("entry_id", entryId)
        }
    }

    /** The completion sheet's payload (`openCompletionSheet`). */
    fun completion(performedOn: String, odometer: String, vendor: String, note: String, tracksOdometer: Boolean): JsonObject = buildJsonObject {
        put("performed_on", performedOn)
        put("odometer", if (tracksOdometer) odometer.trim().toDoubleOrNull()?.let(::number) else null)
        put("vendor", vendor.trim().ifEmpty { null })
        put("note", note.trim().ifEmpty { null })
    }

    /** `addMonths` of the booking picker on `YYYY-MM`. */
    fun shiftMonth(ym: String, n: Int): String = java.time.YearMonth.parse(ym).plusMonths(n.toLong()).toString()

    /** `mayChangeItem`: write, and own or allowed to change others' (`inventory_edit_others`). */
    fun mayChange(item: InventoryItem?, canWrite: Boolean, userId: Int, editOthers: Boolean): Boolean =
        item != null && canWrite && (item.createdBy == userId || editOthers)

    // ── Errors (friendly-error.js) ──

    private val CASES = mapOf(
        "inventory_item_not_found" to "inventory.errorItemGone",
        "inventory_tracked_date_not_found" to "inventory.errorTrackedDateGone",
        "inventory_selected_location_not_found" to "inventory.errorSelectedLocationGone",
        "inventory_booking_not_found" to "inventory.errorBookingGone",
        "inventory_booking_already_linked" to "inventory.errorBookingAlreadyLinked",
        "inventory_location_exists" to "inventory.errorLocationExists",
        "inventory_location_not_found" to "inventory.errorLocationGone",
        "inventory_category_not_found" to "inventory.errorCategoryGone",
        "category_exists" to "category.errorExists",
        "category_last" to "category.errorLast",
    )

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "settings.permReadOnlyBanner",
        "csrf_invalid" to "common.errorFormExpired",
        "not_owner" to "common.errorNotOwner",
    )

    val GONE = setOf(
        "inventory_item_not_found", "inventory_tracked_date_not_found", "inventory_selected_location_not_found",
        "inventory_booking_not_found", "inventory_location_not_found", "inventory_category_not_found",
    )

    fun errorSentence(e: Throwable, strings: Strings, fallback: String = "common.errorGeneric"): String {
        val http = e as? ApiProblem.Http
        return when {
            e is ApiProblem.Network -> strings.t("common.errorOfflineMutation")
            http == null -> strings.t(fallback)
            http.reason == "restore_in_progress" -> strings.t("common.errorRestoreInProgress")
            http.status == 403 -> strings.t(REFUSALS[http.reason] ?: "common.errorNoPermission")
            http.status == 429 -> strings.t(CASES[http.reason] ?: "common.errorRateLimited")
            http.reason in CASES -> strings.t(CASES.getValue(http.reason!!))
            http.operationUnsupported -> strings.t("android.error.unsupported")
            else -> strings.t(fallback)
        }
    }

    fun loadErrorSentence(e: Throwable, strings: Strings): String {
        val http = e as? ApiProblem.Http
        return when {
            e is ApiProblem.Network -> strings.t("common.errorOffline")
            http != null && (http.status == 403 || http.reason in CASES) -> errorSentence(e, strings)
            http?.status == 404 -> strings.t("common.errorNotFound")
            http != null && http.status >= 500 -> strings.t("common.errorServer")
            else -> strings.t("common.errorGeneric")
        }
    }

    /** `isGone`: a reason that says the row is gone, not a refusal. */
    fun isGone(e: Throwable, reason: String? = null): Boolean =
        (e as? ApiProblem.Http)?.let { it.status != 403 && it.reason in GONE && (reason == null || it.reason == reason) } == true

    /** The manager's own sentences (`_errMsg`), and "Other" cannot go. */
    fun managerError(e: Throwable, strings: Strings): String {
        val http = e as? ApiProblem.Http
        return when (http?.reason) {
            "category_exists" -> strings.t("category.errorExists")
            "category_last" -> strings.t("category.errorLast")
            else -> errorSentence(e, strings)
        }
    }
}
