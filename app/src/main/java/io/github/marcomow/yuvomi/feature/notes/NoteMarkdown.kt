package io.github.marcomow.yuvomi.feature.notes

/**
 * Upstream's note Markdown (`renderMarkdownLight` in `public/utils/html.js`) as blocks, and its
 * checklist rule (`public/utils/markdown-checklist.js`), ported for the board, the read view and
 * the `/check` call (NOTES-R11, R15). Line numbers count `\r\n`, `\n` and `\r` alike, exactly
 * as upstream's renderer and route do.
 */
object NoteMarkdown {

    sealed interface Block
    data object Rule : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Quote(val text: String) : Block
    /** A checklist line; [line] is its zero-based source line, [source] the line as stored. */
    data class Check(val line: Int, val checked: Boolean, val text: String, val source: String) : Block
    data class Bullet(val text: String) : Block
    /** [number] counts from 1 within its list, as an HTML `<ol>` does. */
    data class Numbered(val number: Int, val text: String) : Block
    /** Consecutive text lines; upstream joins them with `<br>`. */
    data class Paragraph(val lines: List<String>) : Block
    /** A blank line between lists or paragraphs. */
    data object Gap : Block

    data class ChecklistItem(val checked: Boolean, val prefix: String, val suffix: String, val text: String)

    private val CHECKLIST = Regex("""^( {0,3}[-*+]\s+\[)([ xX])(]\s+.*)$""")
    private val RULE = Regex("""^ {0,3}(-{3,}|\*{3,}|_{3,})\s*$""")
    private val HEADING = Regex("""^ {0,3}(#{1,3})\s+(.+?)\s*#*\s*$""")
    private val QUOTE = Regex("""^ {0,3}>\s?(.*)$""")
    private val BULLET = Regex("""^ {0,3}[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^ {0,3}\d+[.)]\s+(.*)$""")

    /** `matchChecklistLine`. */
    fun matchChecklist(line: String): ChecklistItem? {
        val m = CHECKLIST.matchEntire(line) ?: return null
        val suffix = m.groupValues[3]
        return ChecklistItem(m.groupValues[2].lowercase() == "x", m.groupValues[1], suffix, suffix.replace(Regex("""^]\s+"""), ""))
    }

    /** Source lines without their line endings; index i is upstream's `splitKeepingLineEndings(c)[i * 2]`. */
    fun lines(content: String): List<String> = content.split(Regex("\r\n|\n|\r"))

    /** The line a tap on checklist line [line] must name as `expect`, or null when there is none. */
    fun expectAt(content: String, line: Int): String? = lines(content).getOrNull(line)

    /**
     * `toggleChecklistLine` for the optimistic update: the content with line [line] ticked or
     * unticked, every other character (line endings included) unchanged; null when the line is
     * gone, differs from [expect], or is no checklist line.
     */
    fun toggle(content: String, line: Int, checked: Boolean, expect: String?): String? {
        if (line < 0) return null
        val parts = splitKeepingEndings(content)
        val at = line * 2
        if (at >= parts.size) return null
        val current = parts[at]
        if (expect != null && current != expect) return null
        val item = matchChecklist(current) ?: return null
        if (item.checked == checked) return content
        parts[at] = item.prefix + (if (checked) "x" else " ") + item.suffix
        return parts.joinToString("")
    }

    private fun splitKeepingEndings(content: String): MutableList<String> {
        val out = mutableListOf<String>()
        var last = 0
        for (m in Regex("\r\n|\n|\r").findAll(content)) {
            out += content.substring(last, m.range.first)
            out += m.value
            last = m.range.last + 1
        }
        out += content.substring(last)
        return out
    }

    /** `renderMarkdownLight` as blocks. */
    fun parse(text: String): List<Block> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        var listKind: String? = null
        var number = 0
        fun flush() {
            if (para.isNotEmpty()) {
                out += Paragraph(para.toList())
                para.clear()
            }
        }
        fun list(kind: String?) {
            if (listKind != kind) number = 0
            listKind = kind
        }
        lines(text).forEachIndexed { index, line ->
            if (RULE.matches(line)) {
                flush(); list(null); out += Rule; return@forEachIndexed
            }
            HEADING.matchEntire(line)?.let {
                flush(); list(null); out += Heading(it.groupValues[1].length, it.groupValues[2]); return@forEachIndexed
            }
            QUOTE.matchEntire(line)?.let {
                flush(); list(null); out += Quote(it.groupValues[1]); return@forEachIndexed
            }
            matchChecklist(line)?.let {
                flush(); list("check"); out += Check(index, it.checked, it.text, line); return@forEachIndexed
            }
            BULLET.matchEntire(line)?.let {
                flush(); list("ul"); out += Bullet(it.groupValues[1]); return@forEachIndexed
            }
            NUMBERED.matchEntire(line)?.let {
                flush(); list("ol"); number += 1; out += Numbered(number, it.groupValues[1]); return@forEachIndexed
            }
            if (line.isBlank()) {
                flush(); list(null)
                if (out.isNotEmpty() && out.last() != Gap) out += Gap
                return@forEachIndexed
            }
            list(null)
            para += line
        }
        flush()
        while (out.lastOrNull() == Gap) out.removeAt(out.lastIndex)
        return out
    }

    // ── Inline marks (`inlineMarkdown`) ──

    enum class Mark { BOLD, ITALIC, STRIKE, UNDERLINE, CODE }

    sealed interface Span
    data class Text(val text: String, val marks: Set<Mark>) : Span
    data class Link(val text: String, val url: String, val marks: Set<Mark>) : Span

    private val CODE = Regex("""`([^`]+?)`""")
    private val LINK = Regex("""\[([^\]]+?)]\(([^)\s]+?)\)""")
    private val SAFE_URL = Regex("""^(https?://|mailto:)""", RegexOption.IGNORE_CASE)
    private val EMPHASIS = listOf(
        Regex("""\*\*(.+?)\*\*""") to Mark.BOLD,
        Regex("""~~(.+?)~~""") to Mark.STRIKE,
        Regex("""\*([^*]+?)\*""") to Mark.ITALIC,
        Regex("""<u>(.+?)</u>""") to Mark.UNDERLINE,
    )

    /**
     * One line's inline marks: code first (marks inside stay literal), then links with a safe
     * scheme only, then bold, strikethrough, italic and the editor's `<u>` underline.
     */
    fun inline(text: String, marks: Set<Mark> = emptySet()): List<Span> {
        CODE.find(text)?.let { m ->
            return inline(text.substring(0, m.range.first), marks) + Text(m.groupValues[1], marks + Mark.CODE) +
                inline(text.substring(m.range.last + 1), marks)
        }
        LINK.findAll(text).firstOrNull { SAFE_URL.containsMatchIn(it.groupValues[2]) }?.let { m ->
            val label = inline(m.groupValues[1], marks).joinToString("") { s -> (s as? Text)?.text ?: (s as Link).text }
            return inline(text.substring(0, m.range.first), marks) + Link(label, m.groupValues[2], marks) +
                inline(text.substring(m.range.last + 1), marks)
        }
        val first = EMPHASIS.mapNotNull { (re, mark) -> re.find(text)?.let { it to mark } }.minByOrNull { it.first.range.first }
        if (first != null) {
            val (m, mark) = first
            return inline(text.substring(0, m.range.first), marks) + inline(m.groupValues[1], marks + mark) +
                inline(text.substring(m.range.last + 1), marks)
        }
        return if (text.isEmpty()) emptyList() else listOf(Text(text, marks))
    }

    /** `stripInlineMarkdown`, for a checkbox's spoken name. */
    fun plain(text: String): String = inline(text).joinToString("") { (it as? Text)?.text ?: (it as Link).text }.trim()
}
