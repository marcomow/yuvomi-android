package io.github.marcomow.yuvomi.feature.tags

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import org.junit.Assert.assertEquals
import org.junit.Test

class HashtagLinksTest {
    private fun links(text: AnnotatedString) = text.getLinkAnnotations(0, text.length).sortedBy { it.start }

    @Test fun `each hashtag becomes a link that opens its tag`() {
        val opened = mutableListOf<String>()
        val out = linkHashtags(AnnotatedString("Plan #garden and #Kids"), Color.Red) { opened += it }
        val found = links(out)
        assertEquals(listOf("#garden", "#Kids"), found.map { out.text.substring(it.start, it.end) })
        found.forEach { (it.item as LinkAnnotation.Clickable).linkInteractionListener!!.onClick(it.item) }
        assertEquals(listOf("garden", "Kids"), opened)
    }

    @Test fun `text without hashtags is returned unchanged`() {
        val text = AnnotatedString("nothing #1 here")
        assertEquals(text, linkHashtags(text, Color.Red) {})
    }

    @Test fun `a hashtag inside an existing link stays part of that link`() {
        val text = buildAnnotatedString {
            withLink(LinkAnnotation.Url("https://x.org/#top")) { append("see #top") }
            append(" and #real")
        }
        val out = links(linkHashtags(text, Color.Red) {})
        assertEquals(2, out.size)
        assertEquals("tag:real", (out[1].item as LinkAnnotation.Clickable).tag)
    }
}
