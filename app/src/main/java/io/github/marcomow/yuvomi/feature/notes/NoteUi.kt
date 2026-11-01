package io.github.marcomow.yuvomi.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.ShellContext

/** The member's rights on the board; [offline] makes everything read-only (ADR 0007). */
fun ShellContext.noteViewer(offline: Boolean): NoteViewer {
    val admin = session.isAdmin
    return NoteViewer(
        userId = session.user.id,
        isAdmin = admin,
        readOnly = offline || notesReadOnly(),
        canEditOthers = admin || session.permissions.capability("notes_edit_others"),
    )
}

/** `notes` access is `read` (`isNavModuleReadOnly('notes')`). Admins always write. */
fun ShellContext.notesReadOnly(): Boolean = !session.isAdmin && session.access("notes") != "write"

fun scopeIcon(category: NoteCategory): ImageVector = if (category.isPersonal) Icons.Outlined.Person else Icons.Outlined.Home

@Composable
fun scopeLabel(category: NoteCategory): String = t(if (category.isPersonal) "noteCategories.personal" else "noteCategories.household")

/** `renderCategoryBadge`: name with the scope's glyph; the scope is spoken. */
@Composable
fun CategoryBadge(category: NoteCategory) {
    val scope = scopeLabel(category)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.semantics { contentDescription = "${category.name} ($scope)" },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(scopeIcon(category), null, Modifier.size(14.dp), tint = Yuvomi.colors.textSecondary)
            Spacer(Modifier.width(4.dp))
            Text(category.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryBadges(categories: List<NoteCategory>, modifier: Modifier = Modifier) {
    if (categories.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        categories.forEach { CategoryBadge(it) }
    }
}

/** The card's footer: creator's colour disc and name, then the private and locked marks (`renderAccessMarks`). */
@Composable
fun CreatorLine(note: Note, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MemberAvatar(note.creatorName.orEmpty(), note.creatorColor, note.creatorAvatar, size = 20.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            note.creatorName.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = Yuvomi.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (note.isPrivate) Mark(Icons.Outlined.VisibilityOff, t("common.visibility.private"))
        if (note.locked) Mark(Icons.Outlined.Lock, t("notes.lockedBadge"))
    }
}

@Composable
private fun Mark(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = label, tint = Yuvomi.colors.textSecondary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
}
