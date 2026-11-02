package io.github.marcomow.yuvomi.core.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * UI strings from upstream's locale files (ADR 0011). Lookup is the chosen
 * locale, then English, then the key itself, as upstream's `t()`.
 */
class Strings(
    val locale: String,
    private val table: Map<String, String>,
    private val fallback: Map<String, String>,
    private val pluralCategory: (Double) -> String,
) {
    val isRtl: Boolean get() = locale == "ar" || locale == "fa"

    fun has(key: String): Boolean = key in table || key in fallback

    /** Upstream's `t(key, params)`: plural when `count` is a number, then `{{name}}` filling. */
    fun t(key: String, args: Map<String, Any?> = emptyMap()): String {
        if ('?' in key) {
            // A sentence with the server's numbers ([FriendlyError.withArgs]).
            val (plain, given) = io.github.marcomow.yuvomi.core.api.FriendlyError.decode(key)
            return t(plain, given + args)
        }
        val count = (args["count"] as? Number)?.toDouble()
        val template = if (count != null) resolvePlural(key, count) else lookup(key) ?: key
        val fill = { text: String -> fill(text, args) }
        return if (locale == "ko") KoreanParticles.resolve(template, fill) else fill(template)
    }

    fun t(key: String, vararg args: Pair<String, Any?>): String = t(key, args.toMap())

    private fun lookup(key: String): String? = table[key] ?: fallback[key]

    /** `key_<category>`, then `key_other`, then `key` (upstream's `resolvePluralKey`). */
    private fun resolvePlural(key: String, count: Double): String {
        val category = runCatching { pluralCategory(count) }.getOrDefault("other")
        for (candidate in listOf("${key}_$category", "${key}_other", key)) {
            lookup(candidate)?.let { return it }
        }
        return key
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{\{(\w+)\}\}(\.?)""")

        /** A `{{name}}.` whose value already ends in a full stop keeps one full stop. */
        fun fill(text: String, args: Map<String, Any?>): String = PLACEHOLDER.replace(text) { m ->
            val name = m.groupValues[1]
            val dot = m.groupValues[2]
            if (!args.containsKey(name)) return@replace m.value
            val value = args[name].toString()
            if (dot.isNotEmpty() && value.endsWith('.')) value else value + dot
        }

        /** Nested upstream JSON to `a.b.c` keys. */
        fun flatten(json: String): Map<String, String> {
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyMap()
            val out = HashMap<String, String>(4096)
            fun walk(obj: JsonObject, prefix: String) {
                for ((k, v) in obj) {
                    val key = if (prefix.isEmpty()) k else "$prefix.$k"
                    when (v) {
                        is JsonObject -> walk(v, key)
                        is JsonPrimitive -> if (v.isString) out[key] = v.content
                        else -> Unit
                    }
                }
            }
            walk(root, "")
            return out
        }
    }
}
