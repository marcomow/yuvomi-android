package io.github.marcomow.yuvomi.feature.tags

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell

/**
 * A tag as a small chip, `#garden`, that opens everything carrying it (TAGS-R8). Any module
 * can show its tags with it; [onClick] replaces the default navigation to [TagsRoutes.tag]
 * (for example to filter a list instead).
 */
@Composable
fun TagChip(tag: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shell = LocalShell.current
    val label = "#" + tag.removePrefix("#")
    val open = onClick ?: { shell.navigate(TagsRoutes.tag(tag)) }
    val description = t("android.tags.open", "tag" to tag.removePrefix("#"))
    val tone = Yuvomi.colors.module("tasks")
    Surface(
        onClick = open,
        shape = RoundedCornerShape(50),
        color = tone.copy(alpha = 0.12f),
        contentColor = tone,
        modifier = modifier
            .heightIn(min = 28.dp)
            .semantics { contentDescription = description; role = Role.Button },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
