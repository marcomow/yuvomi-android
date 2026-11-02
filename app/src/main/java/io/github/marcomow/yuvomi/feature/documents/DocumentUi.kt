package io.github.marcomow.yuvomi.feature.documents

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PersonPin
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.LocalDate

/** `CATEGORY_ICONS` of the page, in Material's set. */
fun categoryIcon(category: String): ImageVector = when (category) {
    "medical" -> Icons.Outlined.MedicalServices
    "school" -> Icons.Outlined.School
    "identity" -> Icons.Outlined.Badge
    "insurance" -> Icons.Outlined.VerifiedUser
    "finance" -> Icons.Outlined.AccountBalance
    "home" -> Icons.Outlined.Home
    "vehicle" -> Icons.Outlined.DirectionsCar
    "legal" -> Icons.Outlined.Gavel
    "travel" -> Icons.Outlined.Flight
    "pets" -> Icons.Outlined.Pets
    "warranty" -> Icons.Outlined.Receipt
    "taxes" -> Icons.Outlined.RequestQuote
    "work" -> Icons.Outlined.Work
    else -> Icons.Outlined.Folder
}

@Composable
fun categoryLabel(category: String): String {
    val strings = LocalStrings.current
    val key = "documents.category.$category"
    return if (strings.has(key)) strings.t(key) else category
}

/** The tone of the module (`--module-documents`). */
val documentsTone: Color @Composable get() = Yuvomi.colors.module("documents")

/**
 * The row's icon slot: the list preview when there is one, else the category glyph
 * (`renderDocIconSlot`, `wireLocalThumbs`).
 */
@Composable
fun DocumentThumb(doc: Document, thumbs: DocumentThumbs?, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { (size * 2).roundToPx() }
    val image by produceState(thumbs?.cached(doc), doc.id, doc.updatedAt) {
        if (thumbs != null && thumbs.wants(doc)) value = thumbs.get(doc, px)
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.size(size).clip(shape).background(documentsTone.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val b = image
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(categoryIcon(doc.category), null, tint = documentsTone, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** `expiryChipHtml`: short form on rows, danger tone when expired, warning when soon. */
@Composable
fun ExpiryBadge(doc: Document, today: LocalDate, short: Boolean) {
    val chip = DocumentRules.expiryChip(DocumentRules.dateStatus(doc.expiresAt, today)) ?: return
    val tone = if (chip.expired) Yuvomi.colors.danger else Yuvomi.colors.warning
    Badge(Icons.Outlined.EventBusy, t(if (short) chip.shortKey else chip.key, "count" to chip.count), tone)
}

@Composable
fun Badge(icon: ImageVector?, text: String, tone: Color = Yuvomi.colors.textSecondary) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(tone.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = tone, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MetaItem(icon: ImageVector?, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (icon != null) Icon(icon, null, tint = Yuvomi.colors.textSecondary, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, maxLines = 1)
    }
}

/**
 * `renderMeta`: expiry first, then date and size, kind, place and visibility (only when not
 * the default), then where the file is stored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentMetaLine(doc: Document, selectedFolder: String, today: LocalDate, withStats: Boolean = true) {
    val shell = LocalShell.current
    val label = categoryLabel(doc.category)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ExpiryBadge(doc, today, short = true)
        if (withStats) {
            val day = HouseholdTime.date(doc.updatedAt)?.let { shell.formats.date(it, today) }
            MetaItem(null, listOfNotNull(day, DocumentRules.fileSize(doc.fileSize)).joinToString(" · "))
        }
        MetaItem(categoryIcon(doc.category), label)
        if (DocumentRules.showsFolderChip(doc, label, selectedFolder)) MetaItem(Icons.Outlined.Folder, doc.folderName.orEmpty())
        if (!shell.session.soloHousehold && DocumentRules.showsVisibility(doc.visibility)) {
            MetaItem(
                if (doc.visibility == DocumentRules.VISIBILITY_PRIVATE) Icons.Outlined.Lock else Icons.Outlined.PersonPin,
                t("documents.visibility.${doc.visibility}"),
            )
        }
        DocumentRules.storageBadgeKey(doc)?.let { key ->
            val unavailable = key == "documents.storageDmsUnavailable"
            Badge(if (key == "documents.storageLocalFolder") Icons.Outlined.Storage else Icons.Outlined.Cloud, t(key), if (unavailable) Yuvomi.colors.danger else Yuvomi.colors.textSecondary)
        }
    }
}

/** `selectModal`: one choice from a list; null value means "none". */
data class Choice(val value: String, val label: String, val depth: Int = 0)

@Composable
fun ChoiceDialog(title: String, choices: List<Choice>, selected: String? = null, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(choices, key = { it.value }) { choice ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onChoose(choice.value) }.padding(start = (choice.depth * 16).dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice.value == selected, onClick = { onChoose(choice.value) })
                        Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/** `confirmModal`. */
@Composable
fun ConfirmDialog(title: String, detail: String?, confirm: String, danger: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = detail?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) Yuvomi.colors.danger else MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/** The folder choices of a picker: "no folder" (or the top level), then the whole tree, indented. */
@Composable
fun folderChoices(folders: List<DocumentFolder>, rootKey: String, rows: List<DocumentRules.FolderRow> = DocumentRules.flatten(folders)): List<Choice> =
    listOf(Choice("", t(rootKey))) + rows.map { Choice(it.folder.id.toString(), it.folder.name, it.depth) }

/** A labelled value of the read view (`readRowHtml`). */
@Composable
fun ReadRow(icon: ImageVector, label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = Yuvomi.colors.textSecondary, modifier = Modifier.size(20.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
