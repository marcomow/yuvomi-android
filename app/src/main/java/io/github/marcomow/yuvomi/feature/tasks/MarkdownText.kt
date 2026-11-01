package io.github.marcomow.yuvomi.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * The task description as Markdown (TASKS-R25): headings, lists, checklists,
 * bold, italic, code and links. Checklist lines are tappable (TASKS-R33) and
 * report their zero-based source line, as upstream's `/check` expects.
 */
@Composable
fun MarkdownText(source: String, linkColor: Color, onCheck: ((line: Int, checked: Boolean, shown: String) -> Unit)?) {
    val lines = Checklist.lines(source)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEachIndexed { index, line ->
            val item = Checklist.match(line)
            when {
                item != null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (onCheck != null) Modifier.clickable { onCheck(index, !item.checked, line) } else Modifier,
                ) {
                    Checkbox(checked = item.checked, onCheckedChange = null, enabled = onCheck != null, modifier = Modifier.padding(end = 4.dp))
                    Text(
                        inline(item.text, linkColor),
                        textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                    )
                }
                line.isBlank() -> Text("")
                line.startsWith("### ") -> Text(inline(line.drop(4), linkColor), style = MaterialTheme.typography.titleSmall)
                line.startsWith("## ") -> Text(inline(line.drop(3), linkColor), style = MaterialTheme.typography.titleMedium)
                line.startsWith("# ") -> Text(inline(line.drop(2), linkColor), style = MaterialTheme.typography.titleLarge)
                Regex("""^\s{0,3}[-*+]\s+""").containsMatchIn(line) -> Row {
                    Text("•", modifier = Modifier.width(20.dp))
                    Text(inline(line.replace(Regex("""^\s{0,3}[-*+]\s+"""), ""), linkColor))
                }
                Regex("""^\s{0,3}\d+[.)]\s+""").containsMatchIn(line) -> Row {
                    val number = Regex("""^\s{0,3}(\d+[.)])""").find(line)!!.groupValues[1]
                    Text(number, modifier = Modifier.width(28.dp))
                    Text(inline(line.replace(Regex("""^\s{0,3}\d+[.)]\s+"""), ""), linkColor))
                }
                else -> Text(inline(line, linkColor))
            }
        }
    }
}

private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*|_(.+?)_|`(.+?)`|\[([^\]]+)]\((https?://[^)\s]+)\)|(https?://\S+)""")

/** Bold, italic, code and links on one line. */
fun inline(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[5]) }
            g[6].isNotEmpty() -> withLink(link(g[7], linkColor)) { append(g[6]) }
            g[8].isNotEmpty() -> withLink(link(g[8], linkColor)) { append(g[8]) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

private fun link(url: String, color: Color) =
    LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)))
