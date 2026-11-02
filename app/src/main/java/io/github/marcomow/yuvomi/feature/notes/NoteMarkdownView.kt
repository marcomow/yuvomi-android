package io.github.marcomow.yuvomi.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t

/**
 * A note's text as upstream renders it (NOTES-R11). With [onCheck], checklist boxes are
 * controls that report their source line; without, they are signs whose spoken name says their
 * state (`stateLabels`, upstream #467). [previewLines] caps the text lines of a board card the
 * way the web app's line clamp does; checklist lines are never cut, as on the web.
 */
@Composable
fun NoteMarkdownView(
    content: String,
    onCheck: ((line: Int, checked: Boolean, source: String) -> Unit)?,
    modifier: Modifier = Modifier,
    previewLines: Int? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = NoteMarkdown.parse(content)
    var budget = previewLines ?: Int.MAX_VALUE
    val doneLabel = t("notes.checklistDone")
    val openLabel = t("notes.checklistOpen")
    val toggleLabel = t("notes.checklistToggle")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (block in blocks) {
            if (block !is NoteMarkdown.Check && budget <= 0) continue
            when (block) {
                is NoteMarkdown.Check -> CheckRow(block, linkColor, onCheck, doneLabel, openLabel, toggleLabel)
                is NoteMarkdown.Paragraph -> {
                    val lines = if (previewLines != null) block.lines.take(budget) else block.lines
                    budget -= lines.size
                    Text(
                        buildAnnotatedString {
                            lines.forEachIndexed { i, line ->
                                if (i > 0) append('\n')
                                append(styled(line, linkColor))
                            }
                        },
                        maxLines = if (previewLines != null) lines.size.coerceAtLeast(1) else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is NoteMarkdown.Heading -> {
                    budget -= 1
                    Text(
                        styled(block.text, linkColor),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        maxLines = if (previewLines != null) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is NoteMarkdown.Quote -> {
                    budget -= 1
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).heightIn(min = 18.dp).background(Yuvomi.colors.border))
                        Text(
                            styled(block.text, linkColor),
                            color = Yuvomi.colors.textSecondary,
                            modifier = Modifier.padding(start = 8.dp),
                            maxLines = if (previewLines != null) 1 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                is NoteMarkdown.Bullet -> {
                    budget -= 1
                    Row {
                        Text("•", modifier = Modifier.width(18.dp))
                        Text(styled(block.text, linkColor), maxLines = if (previewLines != null) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                    }
                }
                is NoteMarkdown.Numbered -> {
                    budget -= 1
                    Row {
                        Text("${block.number}.", modifier = Modifier.width(26.dp))
                        Text(styled(block.text, linkColor), maxLines = if (previewLines != null) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                    }
                }
                NoteMarkdown.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
                NoteMarkdown.Gap -> Box(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun CheckRow(
    block: NoteMarkdown.Check,
    linkColor: Color,
    onCheck: ((Int, Boolean, String) -> Unit)?,
    doneLabel: String,
    openLabel: String,
    toggleLabel: String,
) {
    val name = NoteMarkdown.plain(block.text)
    val rowModifier = if (onCheck != null) {
        Modifier
            .fillMaxWidth()
            .toggleable(value = block.checked, role = Role.Checkbox) { onCheck(block.line, it, block.source) }
            .semantics { contentDescription = name.ifEmpty { toggleLabel } }
    } else {
        Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "$name: ${if (block.checked) doneLabel else openLabel}"
        }
    }
    Row(rowModifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (block.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (onCheck != null) MaterialTheme.colorScheme.primary else Yuvomi.colors.textSecondary,
            modifier = Modifier.padding(end = 8.dp).size(22.dp),
        )
        Text(
            styled(block.text, linkColor),
            textDecoration = if (block.checked) TextDecoration.LineThrough else null,
            color = if (block.checked) Yuvomi.colors.textSecondary else Color.Unspecified,
        )
    }
}

/** One line with its inline marks and links. */
fun styled(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    for (span in NoteMarkdown.inline(text)) {
        when (span) {
            is NoteMarkdown.Text -> withStyle(style(span.marks)) { append(span.text) }
            is NoteMarkdown.Link -> withLink(
                LinkAnnotation.Url(span.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
            ) { withStyle(style(span.marks)) { append(span.text) } }
        }
    }
}

private fun style(marks: Set<NoteMarkdown.Mark>): SpanStyle {
    val decorations = listOfNotNull(
        TextDecoration.LineThrough.takeIf { NoteMarkdown.Mark.STRIKE in marks },
        TextDecoration.Underline.takeIf { NoteMarkdown.Mark.UNDERLINE in marks },
    )
    return SpanStyle(
        fontWeight = if (NoteMarkdown.Mark.BOLD in marks) FontWeight.Bold else null,
        fontStyle = if (NoteMarkdown.Mark.ITALIC in marks) FontStyle.Italic else null,
        fontFamily = if (NoteMarkdown.Mark.CODE in marks) FontFamily.Monospace else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}
