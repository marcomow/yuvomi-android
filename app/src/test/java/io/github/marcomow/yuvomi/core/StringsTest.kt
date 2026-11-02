package io.github.marcomow.yuvomi.core

import io.github.marcomow.yuvomi.core.i18n.KoreanParticles
import io.github.marcomow.yuvomi.core.i18n.LocalePicker
import io.github.marcomow.yuvomi.core.i18n.Strings
import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR 0011: upstream's `t()` semantics. */
class StringsTest {

    private val english = mapOf(
        "a.count" to "{{count}} items",
        "a.count_one" to "{{count}} item",
        "a.dot" to "Done by {{name}}.",
        "a.keep" to "Hi {{missing}}",
    )

    private fun strings(own: Map<String, String> = emptyMap(), category: (Double) -> String = { if (it == 1.0) "one" else "other" }) =
        Strings("en", own, english, category)

    @Test fun `plural picks the category key, then other, then the bare key`() {
        assertEquals("1 item", strings().t("a.count", "count" to 1))
        assertEquals("5 items", strings().t("a.count", "count" to 5))
        val polish = strings(mapOf("a.count_many" to "{{count}} rzeczy"), category = { "many" })
        assertEquals("5 rzeczy", polish.t("a.count", "count" to 5))
    }

    @Test fun `a placeholder ending in a full stop is not doubled`() {
        assertEquals("Done by Leo.", strings().t("a.dot", "name" to "Leo"))
        assertEquals("Done by Dr.", strings().t("a.dot", "name" to "Dr."))
    }

    @Test fun `unknown placeholders and keys stay visible`() {
        assertEquals("Hi {{missing}}", strings().t("a.keep"))
        assertEquals("no.such.key", strings().t("no.such.key"))
    }

    @Test fun `own locale wins over English`() =
        assertEquals("x", strings(mapOf("a.keep" to "x")).t("a.keep"))

    @Test fun `flatten nested upstream json`() {
        val flat = Strings.flatten("""{"common":{"save":"Save","visibility":{"all":"Everyone"}},"n":1}""")
        assertEquals(mapOf("common.save" to "Save", "common.visibility.all" to "Everyone"), flat)
    }

    @Test fun `korean particles follow the last syllable`() {
        assertEquals("사과를", KoreanParticles.resolve("사과을(를)"))
        assertEquals("책을", KoreanParticles.resolve("책을(를)"))
        assertEquals("서울로", KoreanParticles.resolve("서울(으)로"))
        assertEquals("ABC을(를)", KoreanParticles.resolve("ABC을(를)"))
    }

    @Test fun `locale picker maps like lang-init`() {
        assertEquals("zh-Hant", LocalePicker.pick(listOf("zh-TW")))
        assertEquals("zh", LocalePicker.pick(listOf("zh-CN")))
        assertEquals("nb", LocalePicker.pick(listOf("no-NO")))
        assertEquals("pt-BR", LocalePicker.pick(listOf("pt-BR")))
        assertEquals("pt", LocalePicker.pick(listOf("pt-PT")))
        assertEquals("de", LocalePicker.pick(listOf("xx", "de-AT")))
        assertEquals("en", LocalePicker.pick(listOf("xx")))
    }

    @Test fun `a key carrying the server's numbers fills them in`() =
        assertEquals("Done by 7.", strings().t("a.dot?name=7"))
}
