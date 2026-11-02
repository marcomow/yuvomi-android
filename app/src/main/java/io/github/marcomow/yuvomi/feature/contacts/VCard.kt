package io.github.marcomow.yuvomi.feature.contacts

import java.nio.charset.Charset

/**
 * The vCard reader of `public/utils/vcard.js`, ported line by line (CONT-R14): unfolding,
 * quoted-printable with its charset, escapes, structured `N`, the first `TEL`, `EMAIL`,
 * `ADR`, `NOTE`, `BDAY` (also without a year) and `CATEGORIES`.
 */
object VCard {
    data class Parsed(
        val name: String?,
        val firstName: String? = null,
        val lastName: String? = null,
        val middleName: String? = null,
        val namePrefix: String? = null,
        val nameSuffix: String? = null,
        val phone: String? = null,
        val email: String? = null,
        val address: String? = null,
        val notes: String? = null,
        val birthday: String? = null,
        val birthdayYearUnknown: Boolean = false,
        /** The raw `CATEGORIES` value; the page resolves it to a category key. */
        val categories: String = "",
    )

    data class Birthday(val date: String, val yearUnknown: Boolean)

    private fun unescape(s: String?): String =
        Regex("""\\([\\,;nN])""").replace(s.orEmpty()) { m -> if (m.groupValues[1].equals("n", ignoreCase = true)) "\n" else m.groupValues[1] }

    private fun splitUnescaped(value: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                current.append(ch).append(value[i + 1])
                i++
            } else if (ch == separator) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(ch)
            }
            i++
        }
        parts += current.toString()
        return parts
    }

    private val qpParam = Regex("(?:^|;)ENCODING=(?:QUOTED-PRINTABLE|QP)(?:;|$)", RegexOption.IGNORE_CASE)

    private fun isQuotedPrintable(params: String?) = qpParam.containsMatchIn(params.orEmpty())

    private fun charsetOf(params: String?): String =
        Regex("(?:^|;)CHARSET=([^;]+)", RegexOption.IGNORE_CASE).find(params.orEmpty())?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: "utf-8"

    private fun decodeQuotedPrintable(value: String, charset: String): String {
        val joined = value.replace(Regex("=\r?\n"), "")
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        val hex = Regex("^[0-9A-Fa-f]{2}$")
        while (i < joined.length) {
            val ch = joined[i]
            if (ch == '=' && i + 2 < joined.length && hex.matches(joined.substring(i + 1, i + 3))) {
                bytes.write(joined.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                bytes.write(ch.code and 0xff)
                i++
            }
        }
        val cs = runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8)
        return String(bytes.toByteArray(), cs)
    }

    /** `unfoldVCard`: soft line breaks of quoted-printable values first, then RFC folding. */
    private fun unfold(text: String): String {
        val lines = text.split(Regex("\r?\n"))
        val merged = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            var line = lines[i]
            val colon = line.indexOf(':')
            val head = if (colon == -1) line else line.substring(0, colon)
            if (isQuotedPrintable(head)) {
                while (Regex("=[ \t]*$").containsMatchIn(line) && i + 1 < lines.size) {
                    line = line.replace(Regex("=[ \t]*$"), "") + lines[i + 1]
                    i++
                }
            }
            merged += line
            i++
        }
        return merged.joinToString("\n").replace(Regex("\r?\n[ \t]"), "")
    }

    private fun yearless(month: String, day: String): Birthday? {
        val m = month.toIntOrNull() ?: return null
        val d = day.toIntOrNull() ?: return null
        val valid = runCatching { java.time.LocalDate.of(ContactsRules.YEAR_UNKNOWN_PLACEHOLDER, m, d) }.isSuccess
        return if (valid) Birthday("${ContactsRules.YEAR_UNKNOWN_PLACEHOLDER}-$month-$day", true) else null
    }

    /** `parseBirthdayValue`: full dates, `--MMDD`, a bare year, and Apple's omitted year. */
    fun parseBirthday(value: String?, params: String = ""): Birthday? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        val dateTime = Regex("^(\\d{4}-?\\d{2}-?\\d{2}|--\\d{2}-?\\d{2})T\\d").find(trimmed)
        val datePart = dateTime?.groupValues?.get(1) ?: trimmed
        Regex("^--(\\d{2})-?(\\d{2})$").find(datePart)?.let { return yearless(it.groupValues[1], it.groupValues[2]) }
        val cleaned = datePart.replace(Regex("[^\\d-]"), "")
        val date = when {
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(cleaned) -> cleaned
            Regex("^\\d{8}$").matches(cleaned) -> "${cleaned.substring(0, 4)}-${cleaned.substring(4, 6)}-${cleaned.substring(6, 8)}"
            Regex("^\\d{4}$").matches(cleaned) -> "$cleaned-01-01"
            else -> return null
        }
        val omit = Regex("(?:^|;)X-APPLE-OMIT-YEAR=(\\d{4})(?:;|$)", RegexOption.IGNORE_CASE).find(params)
        if ((omit != null && omit.groupValues[1] == date.take(4)) || date.startsWith("1604-")) {
            return yearless(date.substring(5, 7), date.substring(8, 10))
        }
        return Birthday(date, false)
    }

    /** `splitVCards`. */
    fun split(text: String): List<String> {
        val matches = Regex("BEGIN:VCARD[\\s\\S]*?END:VCARD", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.toList()
        if (matches.isNotEmpty()) return matches
        return if (text.isNotBlank()) listOf(text) else emptyList()
    }

    /** `parseVCard`. */
    fun parse(text: String): Parsed {
        val unfolded = unfold(text)
        fun field(prop: String): Pair<String, String>? {
            val m = Regex("^$prop(;[^:]*)?:(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(unfolded) ?: return null
            val params = m.groupValues[1].let { if (it.isEmpty()) "" else it.substring(1) }
            return params to m.groupValues[2].trim()
        }
        fun raw(prop: String): String? {
            val (params, value) = field(prop) ?: return null
            return if (isQuotedPrintable(params)) decodeQuotedPrintable(value, charsetOf(params)) else value
        }
        fun get(prop: String): String? = raw(prop)?.let(::unescape)

        val nRaw = raw("N")
        val n = nRaw?.let { splitUnescaped(it, ';').map(::unescape) } ?: emptyList()
        fun part(i: Int) = n.getOrNull(i)?.trim()?.ifEmpty { null }
        val last = part(0)
        val first = part(1)
        val middle = part(2)
        val name = ContactsRules.composeDisplayName(first, last, middle) ?: get("FN")?.ifEmpty { null }
        val adr = get("ADR")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(", ")?.ifEmpty { null }
        val bdayParams = field("BDAY")?.first
        val birthday = if (bdayParams != null) parseBirthday(get("BDAY"), bdayParams) else null
        return Parsed(
            name = name,
            firstName = first,
            lastName = last,
            middleName = middle,
            namePrefix = part(3),
            nameSuffix = part(4),
            phone = get("TEL")?.ifEmpty { null },
            email = get("EMAIL")?.ifEmpty { null },
            address = adr,
            notes = get("NOTE")?.ifEmpty { null },
            birthday = birthday?.date,
            birthdayYearUnknown = birthday?.yearUnknown ?: false,
            categories = get("CATEGORIES").orEmpty(),
        )
    }

    fun parseAll(text: String): List<Parsed> = split(text).map(::parse)
}
