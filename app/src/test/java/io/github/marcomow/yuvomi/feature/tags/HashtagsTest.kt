package io.github.marcomow.yuvomi.feature.tags

import org.junit.Assert.assertEquals
import org.junit.Test

class HashtagsTest {
    @Test fun `finds hashtags at the start, after spaces and punctuation`() =
        assertEquals(setOf("garden", "Urlaub2026", "kids_party"), Hashtags.extract("#garden plan (#Urlaub2026), for #kids_party."))

    @Test fun `keeps the first spelling per key and reads several texts`() =
        assertEquals(setOf("Garten"), Hashtags.extract("#Garten", "more #garten and #GARTEN"))

    @Test fun `ignores numbers, hex colours, urls, entities and headings`() =
        assertEquals(
            emptySet<String>(),
            Hashtags.extract("#1 #42 #ff0000 #a1b2c3 https://x.org/page#part &#39; # Heading ##double"),
        )

    @Test fun `a word that only looks like hex is still a tag`() =
        assertEquals(setOf("cafe", "bad"), Hashtags.extract("#cafe #bad"))

    @Test fun `trims trailing dashes and accepts non-latin letters`() =
        assertEquals(setOf("work", "äpfel", "日本"), Hashtags.extract("#work- #äpfel #日本"))

    @Test fun `tag keys fold case and composition like upstream`() {
        assertEquals(TagKey.of("ÄPFEL"), TagKey.of("äpfel"))
        assertEquals("garten", TagKey.of("Garten"))
    }
}
