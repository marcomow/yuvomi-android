package io.github.marcomow.yuvomi.core.api

/**
 * Upstream's shared error sentences (public/utils/friendly-error.js, `knownKey` and
 * `knownSentence`), from the generated [FriendlyErrorTable]. Each module maps the reasons its
 * own page reads; this is what every page falls back to, so a reason no module names still gets
 * its sentence, not the general one. The server's English text is never shown (ARCHITECTURE.md,
 * rule 4); from the answer only the whole numbers `CASE_PARAMS` allows for the case are used.
 *
 * A sentence with numbers travels as one string through the modules' existing "i18n key"
 * plumbing: [withArgs] appends them as `key?name=value&...`, and
 * [io.github.marcomow.yuvomi.core.i18n.Strings.t] reads them back ([decode]). Keys never
 * contain `?`.
 */
object FriendlyError {
    /** The key `knownKey` gives for [e], with its numbers ([withArgs]); null when upstream has none or a number is missing. */
    fun key(e: Throwable): String? {
        if (e !is ApiProblem.Http) return null
        if (e.reason == "restore_in_progress") return "common.errorRestoreInProgress"
        val key = when (e.status) {
            403 -> FriendlyErrorTable.REFUSALS[e.reason] ?: "common.errorNoPermission"
            429 -> FriendlyErrorTable.CASES[e.reason] ?: "common.errorRateLimited"
            else -> FriendlyErrorTable.CASES[e.reason] ?: return null
        }
        val names = if (e.status != 403 && FriendlyErrorTable.CASES[e.reason] == key) FriendlyErrorTable.PARAMS[e.reason] else null
        if (names == null) return key
        // `knownSentence`: a sentence whose numbers are missing is no sentence.
        return args(e)?.let { encode(key, it) }
    }

    /**
     * `errorSentence(err, fallback)` around a module's own mapping: what the module chose, with
     * its numbers; when it chose only the general sentence of the place ([fallback]), upstream's
     * shared sentence for the reason, if there is one.
     */
    fun finish(e: Throwable, chosen: String, fallback: String? = null): String =
        if (fallback != null && chosen == fallback) key(e) ?: fallback else withArgs(e, chosen)

    /** `isGone`: the action's target no longer exists. Never on a 403. */
    fun isGone(e: Throwable): Boolean = e is ApiProblem.Http && e.status != 403 && e.reason in FriendlyErrorTable.GONE

    /**
     * [key] as a module chose it, with the server's numbers when it is the sentence of the case
     * [e] names and that case has numbers (`CASE_PARAMS`). Anything else comes back unchanged,
     * so wrapping a module's mapping in this is always safe.
     */
    fun withArgs(e: Throwable, key: String): String {
        if (e !is ApiProblem.Http || '?' in key) return key
        if (FriendlyErrorTable.CASES[e.reason] != key || e.reason !in FriendlyErrorTable.PARAMS) return key
        return args(e)?.let { encode(key, it) } ?: key
    }

    /** `readParams`: the case's numbers from `params`, or null when one is missing or not a whole number in range. */
    fun args(e: ApiProblem.Http): Map<String, Long>? {
        val names = FriendlyErrorTable.PARAMS[e.reason] ?: return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for (name in names) out[name] = e.params[name] ?: return null
        return out
    }

    fun encode(key: String, args: Map<String, Any>): String =
        if (args.isEmpty()) key else key + "?" + args.entries.joinToString("&") { "${it.key}=${it.value}" }

    /** The key and its numbers from an [encode]d string; a plain key has none. */
    fun decode(encoded: String): Pair<String, Map<String, Any?>> {
        val at = encoded.indexOf('?')
        if (at < 0) return encoded to emptyMap()
        val args = encoded.substring(at + 1).split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq) to (part.substring(eq + 1).toLongOrNull() ?: part.substring(eq + 1))
        }.toMap()
        return encoded.substring(0, at) to args
    }

    /** Whole numbers in `params` that upstream would accept (`readParams`: 0..PARAM_MAX). */
    fun readParams(params: Map<String, kotlinx.serialization.json.JsonElement>?): Map<String, Long> {
        if (params == null) return emptyMap()
        return params.mapNotNull { (name, value) ->
            val p = value as? kotlinx.serialization.json.JsonPrimitive ?: return@mapNotNull null
            if (p.isString) return@mapNotNull null
            val n = p.content.toLongOrNull() ?: return@mapNotNull null
            if (n < 0 || n > FriendlyErrorTable.PARAM_MAX) null else name to n
        }.toMap()
    }
}
