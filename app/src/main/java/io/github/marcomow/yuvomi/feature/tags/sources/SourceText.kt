package io.github.marcomow.yuvomi.feature.tags.sources

import io.github.marcomow.yuvomi.feature.shell.ShellContext
import java.time.LocalDate

/**
 * The words and dates a source puts into its items' subtitles, so the sources' pure `items`
 * functions run without a shell in tests.
 */
class SourceText(
    /** An i18n key to its text. */
    val label: (String) -> String,
    /** An ISO date or date-time (its first ten characters) to the household's short date. */
    val date: (String?) -> String?,
) {
    companion object {
        fun of(shell: ShellContext): SourceText {
            val strings = shell.container.strings.value
            val today = shell.time.today()
            return SourceText(
                label = { key -> strings?.t(key) ?: key },
                date = { iso -> parse(iso)?.let { shell.formats.date(it, today) } },
            )
        }

        /** Keys and ISO dates as they are, for tests. */
        val plain = SourceText(label = { it }, date = { iso -> parse(iso)?.toString() })

        fun parse(iso: String?): LocalDate? = iso?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    /** The non-blank parts joined with ` · `, or null. */
    fun join(vararg parts: String?): String? = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ").ifEmpty { null }
}
