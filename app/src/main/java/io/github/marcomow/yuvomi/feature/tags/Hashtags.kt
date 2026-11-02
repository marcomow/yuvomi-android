package io.github.marcomow.yuvomi.feature.tags

/**
 * `#hashtags` in free text (docs/spec/tags.md, TAGS-R2). Upstream stores tags only on tasks
 * and shopping items; everywhere else a `#word` in an item's own text counts as a tag.
 *
 * A hashtag starts with `#` at the start of the text or after a character that is not a
 * letter, digit, `_`, `#`, `&` or `/` (so `page#part`, `&#39;`, `##` and URLs stay out),
 * and runs over letters, digits, `_` and `-`. It must contain a letter, so `#1` and `#42`
 * (issue numbers, list positions) are not tags, and neither are hex colours like `#fff` or
 * `#a1b2c3`. Trailing `-` and `_` are dropped. At most 64 characters, like upstream's tags.
 */
object Hashtags {
    private val pattern = Regex("""(?<![\p{L}\p{N}_#&/])#([\p{L}\p{N}_-]+)""")
    private val hexColour = Regex("""[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8}""")
    private const val MAX_LEN = 64

    /** A hashtag in a text: where it sits (including the `#`) and the tag without `#`. */
    data class Found(val range: IntRange, val tag: String)

    /** Every hashtag in [text] in order, repeats included, by the rules above. */
    fun find(text: String?): List<Found> {
        if (text.isNullOrEmpty() || '#' !in text) return emptyList()
        return pattern.findAll(text).mapNotNull { m ->
            val raw = m.groupValues[1]
            val tag = raw.trimEnd('-', '_').take(MAX_LEN)
            when {
                tag.none { it.isLetter() } -> null
                hexColour.matches(tag) && tag.any { it.isDigit() } -> null
                // The `#` plus the kept characters; a trimmed tail is not part of the link.
                else -> Found(m.range.first..(m.range.first + tag.length), tag)
            }
        }.toList()
    }

    /** The hashtags in [texts], without `#`, first spelling kept per [TagKey]. */
    fun extract(vararg texts: String?): Set<String> {
        val seen = HashSet<String>()
        val out = LinkedHashSet<String>()
        for (text in texts) for (found in find(text)) if (seen.add(TagKey.of(found.tag))) out += found.tag
        return out
    }
}
