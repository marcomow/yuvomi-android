package io.github.marcomow.yuvomi.core.i18n

/**
 * Port of upstream's `public/utils/korean-particles.js`: picks 을/를, 이/가,
 * 은/는, 과/와 and (으)로 after the filled-in word, depending on whether its last
 * syllable has a final consonant.
 */
object KoreanParticles {

    private data class Form(val form: String, val consonant: String, val vowel: String, val rieulAsVowel: Boolean = false)

    private val FORMS = listOf(
        Form("을(를)", "을", "를"),
        Form("이(가)", "이", "가"),
        Form("은(는)", "은", "는"),
        Form("과(와)", "과", "와"),
        Form("(으)로", "으로", "로", rieulAsVowel = true),
    )
    private val BY_FORM = FORMS.associateBy { it.form }
    private val PATTERN = Regex(FORMS.joinToString("|") { Regex.escape(it.form) })
    private const val FIRST = 0xAC00
    private const val LAST = 0xD7A3
    private const val FINALS = 28
    private const val FINAL_RIEUL = 8
    private val CLOSING_QUOTES = setOf('"', '\'', '”', '’', '」', '』')

    private fun pick(entry: Form, before: CharSequence): String {
        var i = before.length - 1
        while (i >= 0 && before[i] in CLOSING_QUOTES) i--
        if (i < 0) return entry.form
        val code = before[i].code
        if (code < FIRST || code > LAST) return entry.form
        val final = (code - FIRST) % FINALS
        return if (final == 0 || (entry.rieulAsVowel && final == FINAL_RIEUL)) entry.vowel else entry.consonant
    }

    fun resolve(template: String, fill: (String) -> String = { it }): String {
        if ('(' !in template) return fill(template)
        val out = StringBuilder()
        var last = 0
        for (match in PATTERN.findAll(template)) {
            out.append(fill(template.substring(last, match.range.first)))
            out.append(pick(BY_FORM.getValue(match.value), out))
            last = match.range.last + 1
        }
        return out.append(fill(template.substring(last))).toString()
    }
}
