package io.github.marcomow.yuvomi.feature.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.feature.shell.LocalShell

/**
 * Makes every `#hashtag` in [text] a link that opens the tag (TAGS-R13), keeping the text's
 * own styles and links. A hashtag inside an existing link (a URL's `#fragment`, a Markdown link's
 * label) stays part of that link. Pure apart from [open], so it is unit tested.
 */
fun linkHashtags(text: AnnotatedString, color: Color, open: (String) -> Unit): AnnotatedString {
    val found = Hashtags.find(text.text)
    if (found.isEmpty()) return text
    val links = text.getLinkAnnotations(0, text.length)
    val styles = TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.Medium))
    return AnnotatedString.Builder(text).apply {
        for (f in found) {
            val start = f.range.first
            val end = f.range.last + 1
            if (links.any { it.start < end && start < it.end }) continue
            addLink(LinkAnnotation.Clickable("tag:${f.tag}", styles) { open(f.tag) }, start, end)
        }
    }.toAnnotatedString()
}

/** The colour hashtag links and [TagChip]s share. */
@Composable
fun tagTone(): Color = Yuvomi.colors.module("tasks")

/** [linkHashtags] with the shell's navigation to the Tags view. */
@Composable
fun rememberHashtagLinker(): (AnnotatedString) -> AnnotatedString {
    val shell = LocalShell.current
    val tone = tagTone()
    return { linkHashtags(it, tone) { tag -> shell.navigate(TagsRoutes.tag(tag)) } }
}

/** Plain text whose `#hashtags` open the tag; the drop-in for `Text(value)` on item text. */
@Composable
fun HashtagText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val link = rememberHashtagLinker()
    Text(link(AnnotatedString(text)), modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** An item's tags as a row of [TagChip]s; nothing when there are none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(tags: Collection<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (tag in tags) TagChip(tag)
    }
}
