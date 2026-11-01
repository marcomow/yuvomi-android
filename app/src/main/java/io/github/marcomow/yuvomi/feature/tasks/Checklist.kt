package io.github.marcomow.yuvomi.feature.tasks

/** Port of upstream's `public/utils/markdown-checklist.js` (TASKS-R33). */
object Checklist {

    data class Item(val checked: Boolean, val prefix: String, val suffix: String, val text: String)

    private val CHECKLIST = Regex("""^( {0,3}[-*+]\s+\[)([ xX])(\]\s+.*)$""")

    fun match(line: String): Item? {
        val m = CHECKLIST.matchEntire(line) ?: return null
        val suffix = m.groupValues[3]
        return Item(m.groupValues[2].lowercase() == "x", m.groupValues[1], suffix, suffix.replace(Regex("""^\]\s+"""), ""))
    }

    /** Source lines, split on `\r\n`, `\n` or `\r` exactly like upstream, so line numbers agree. */
    fun lines(content: String): List<String> = content.split(Regex("\r\n|\n|\r"))

    /** The line as it will read after ticking, for the optimistic update. */
    fun toggled(line: String, checked: Boolean): String {
        val item = match(line) ?: return line
        return item.prefix + (if (checked) "x" else " ") + item.suffix
    }
}
