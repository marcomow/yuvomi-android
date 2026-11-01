package io.github.marcomow.yuvomi.feature.tasks

/** Port of upstream's `public/utils/mentions.js` (TASKS-R36). */
object Mentions {

    data class Person(val id: Int, val displayName: String)

    sealed interface Segment {
        val text: String

        data class Text(override val text: String) : Segment
        data class Mention(override val text: String, val person: Person) : Segment
    }

    private fun boundary(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** The longest matching display name wins; an `@` right after a word character is not a mention. */
    fun split(text: String, people: List<Person>): List<Segment> {
        val list = people.filter { it.displayName.isNotEmpty() }.sortedByDescending { it.displayName.length }
        val out = mutableListOf<Segment>()
        val plain = StringBuilder()
        var i = 0
        fun flush() {
            if (plain.isNotEmpty()) out += Segment.Text(plain.toString())
            plain.clear()
        }
        while (i < text.length) {
            val isAt = text[i] == '@'
            val boundaryOk = i == 0 || !boundary(text[i - 1])
            if (!isAt || !boundaryOk) {
                plain.append(text[i]); i++; continue
            }
            val rest = text.substring(i + 1)
            val hit = list.firstOrNull { p ->
                val name = p.displayName
                if (!rest.regionMatches(0, name, 0, name.length, ignoreCase = true)) return@firstOrNull false
                val after = rest.getOrNull(name.length)
                after == null || !boundary(after)
            }
            if (hit == null) {
                plain.append(text[i]); i++; continue
            }
            flush()
            val typed = text.substring(i, i + 1 + hit.displayName.length)
            out += Segment.Mention(typed, hit)
            i += typed.length
        }
        flush()
        return out
    }

    fun mentionedIds(text: String, people: List<Person>): List<Int> =
        split(text, people).filterIsInstance<Segment.Mention>().map { it.person.id }.distinct()

    /** The `@query` being typed right before [caret], or null. */
    fun activeQuery(text: String, caret: Int): String? {
        val upto = text.substring(0, caret.coerceIn(0, text.length))
        val at = upto.lastIndexOf('@')
        if (at == -1) return null
        if (at > 0 && boundary(upto[at - 1])) return null
        val query = upto.substring(at + 1)
        if (query.contains('\n') || query.length > 40) return null
        return query
    }

    /** Port of `applyMention`: replaces the `@query` at [caret] with `@Name `. */
    fun apply(text: String, caret: Int, name: String): Pair<String, Int>? {
        if (name.isEmpty()) return null
        val upto = text.substring(0, caret)
        val at = upto.lastIndexOf('@')
        if (at == -1) return null
        if (at > 0 && boundary(upto[at - 1])) return null
        val before = text.substring(0, at)
        val afterAt = text.substring(at + 1)
        var typed = 0
        while (typed < name.length && typed < afterAt.length && afterAt[typed].lowercaseChar() == name[typed].lowercaseChar()) typed++
        val rest = text.substring(caret)
        val wordEnd = rest.indexOfFirst { !boundary(it) }.let { if (it == -1) rest.length else it }
        val consumed = maxOf(typed, (caret - (at + 1)) + wordEnd)
        val after = text.substring(minOf(text.length, at + 1 + consumed))
        val inserted = "@$name" + if (after.isEmpty() || boundary(after[0])) " " else ""
        return (before + inserted + after) to (before.length + inserted.length)
    }
}
