package io.github.marcomow.yuvomi.feature.notes

/**
 * The editor's formatting toolbar (NOTES-R17), ported from upstream's `applyFormat` in
 * `public/utils/markdown-toolbar.js`: marks around the selection, or a marker at the start of
 * the line. Pure, on text plus selection, so it is tested without a text field.
 */
object MarkdownFormat {

    enum class Format(val labelKey: String) {
        BOLD("markdown.bold"),
        ITALIC("markdown.italic"),
        UNDERLINE("markdown.underline"),
        STRIKETHROUGH("markdown.strikethrough"),
        HEADING("markdown.heading"),
        LIST("markdown.list"),
        ORDERED_LIST("markdown.orderedList"),
        CHECKLIST("markdown.checklist"),
        LINK("markdown.link"),
        CODE("markdown.code"),
        QUOTE("markdown.quote"),
        DIVIDER("markdown.divider"),
    }

    /** Text and selection; [start] to [end] is the selection, equal when it is a cursor. */
    data class Edit(val text: String, val start: Int, val end: Int)

    /** Placeholder words come from the UI strings, since they land in the note. */
    data class Placeholders(val text: String, val code: String, val linkText: String, val url: String)

    fun apply(edit: Edit, format: Format, words: Placeholders): Edit {
        val text = edit.text
        val start = minOf(edit.start, edit.end).coerceIn(0, text.length)
        val end = maxOf(edit.start, edit.end).coerceIn(0, text.length)
        val sel = text.substring(start, end)

        fun replace(insert: String, from: Int, to: Int) = text.substring(0, from) + insert + text.substring(to)
        val lineHead = text.lastIndexOf('\n', (start - 1).coerceAtLeast(-1)).let { if (start == 0) 0 else it + 1 }

        fun prefixLine(marker: String): Edit {
            val onEmptyLine = text.substring(lineHead, start).isBlank()
            val insert = if (onEmptyLine) marker else "\n$marker"
            val pos = start + insert.length
            return Edit(replace(insert, start, start), pos, pos)
        }

        fun prefixSelection(marker: String, alreadySet: (String) -> Boolean): Edit {
            val joined = sel.split('\n').joinToString("\n") { if (alreadySet(it)) it else marker + it }
            val pos = start + joined.length
            return Edit(replace(joined, start, end), pos, pos)
        }

        fun wrap(before: String, after: String, placeholder: String): Edit {
            val insert = sel.ifEmpty { placeholder }
            val s = start + before.length
            return Edit(replace(before + insert + after, start, end), s, s + insert.length)
        }

        return when (format) {
            Format.BOLD -> wrap("**", "**", words.text)
            Format.ITALIC -> wrap("*", "*", words.text)
            Format.UNDERLINE -> wrap("<u>", "</u>", words.text)
            Format.STRIKETHROUGH -> wrap("~~", "~~", words.text)
            Format.CODE -> wrap("`", "`", words.code)
            Format.LINK -> if (sel.isNotEmpty()) {
                val s = start + sel.length + 3
                Edit(replace("[$sel](${words.url})", start, end), s, s + words.url.length)
            } else {
                Edit(replace("[${words.linkText}](${words.url})", start, end), start + 1, start + 1 + words.linkText.length)
            }
            Format.HEADING -> {
                val lineEnd = text.indexOf('\n', start).let { if (it == -1) text.length else it }
                val line = text.substring(lineHead, lineEnd)
                val match = Regex("""^(#{1,3})\s""").find(line)
                val next = when {
                    match != null && match.groupValues[1].length < 3 -> "#$line"
                    match != null -> line.replaceFirst(Regex("""^#{1,3}\s"""), "")
                    else -> "## $line"
                }
                val pos = lineHead + next.length
                Edit(replace(next, lineHead, lineEnd), pos, pos)
            }
            Format.LIST -> if (sel.isNotEmpty()) prefixSelection("- ") { it.startsWith("- ") } else prefixLine("- ")
            Format.ORDERED_LIST -> if (sel.isNotEmpty()) {
                val joined = sel.split('\n').mapIndexed { i, l -> "${i + 1}. " + l.replaceFirst(Regex("""^\d+\.\s"""), "") }.joinToString("\n")
                val pos = start + joined.length
                Edit(replace(joined, start, end), pos, pos)
            } else {
                prefixLine("1. ")
            }
            Format.CHECKLIST -> if (sel.isNotEmpty()) prefixSelection("- [ ] ") { it.startsWith("- [ ] ") } else prefixLine("- [ ] ")
            Format.QUOTE -> if (sel.isNotEmpty()) prefixSelection("> ") { it.startsWith("> ") } else prefixLine("> ")
            Format.DIVIDER -> {
                val insert = "\n\n---\n\n"
                val pos = start + insert.length
                Edit(replace(insert, start, end), pos, pos)
            }
        }
    }
}
