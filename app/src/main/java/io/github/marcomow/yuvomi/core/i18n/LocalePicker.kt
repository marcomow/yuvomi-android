package io.github.marcomow.yuvomi.core.i18n

/** Port of upstream's `public/lang-init.js` locale matching (ADR 0011). */
object LocalePicker {

    val SUPPORTED = listOf(
        "de", "en", "es", "fr", "it", "sv", "el", "ru", "tr", "zh", "zh-Hant", "ja", "ar", "hi",
        "pt-BR", "pt", "uk", "pl", "nl", "cs", "vi", "hu", "ko", "id", "fa", "fil", "nb",
    )
    private val REGION_SCRIPT = mapOf("TW" to "Hant", "HK" to "Hant", "MO" to "Hant")
    private val LANGUAGE_ALIAS = mapOf("no" to "nb", "nn" to "nb")

    private fun canonical(tag: String): List<String> = tag.replace('_', '-').split('-').mapIndexed { i, part ->
        when {
            i == 0 -> part.lowercase()
            part.length == 4 -> part.lowercase().replaceFirstChar { it.uppercase() }
            part.length == 2 -> part.uppercase()
            else -> part.lowercase()
        }
    }

    private fun match(parts: List<String>): String? {
        val hasScript = parts.drop(1).any { it.length == 4 }
        val p = parts.toMutableList()
        while (p.isNotEmpty()) {
            val tag = p.joinToString("-")
            if (tag in SUPPORTED) return tag
            if (!hasScript) {
                val script = REGION_SCRIPT[p.last()]
                if (script != null && "${p[0]}-$script" in SUPPORTED) return "${p[0]}-$script"
            }
            p.removeAt(p.lastIndex)
        }
        return null
    }

    /** The first supported locale for the device's preferred language tags, else English. */
    fun pick(tags: List<String>): String {
        for (tag in tags) {
            if (tag.isBlank()) continue
            val parts = canonical(tag)
            match(parts)?.let { return it }
            LANGUAGE_ALIAS[parts[0]]?.let { alias -> match(listOf(alias) + parts.drop(1))?.let { return it } }
        }
        return "en"
    }
}
