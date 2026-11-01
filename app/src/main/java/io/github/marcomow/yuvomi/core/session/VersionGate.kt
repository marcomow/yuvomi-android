package io.github.marcomow.yuvomi.core.session

/** SIGNIN-R14. Port of upstream's `public/utils/version.js` (`compareVersions`). */
object VersionGate {

    enum class Verdict { TOO_OLD, SUPPORTED, NEWER_THAN_TESTED }

    fun verdict(server: String?, minimum: String, pin: String): Verdict {
        val belowMin = compare(server, minimum)
        if (belowMin != null && belowMin < 0) return Verdict.TOO_OLD
        val abovePin = compare(server, pin)
        // An unparsable server version is treated as newer than tested (SIGNIN-R14).
        return if (abovePin == null || abovePin > 0) Verdict.NEWER_THAN_TESTED else Verdict.SUPPORTED
    }

    private data class Parsed(val parts: List<Int>, val prerelease: String)

    private fun parse(value: String?): Parsed? {
        val raw = value?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (raw.isEmpty()) return null
        val core = raw.substringBefore('-')
        val prerelease = if ('-' in raw) raw.substringAfter('-') else ""
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.any { it < 0 }) return null
        return Parsed(parts, prerelease)
    }

    /** `<0` when [a] is older, `0` when equal, `>0` when newer, `null` when either is unreadable. */
    fun compare(a: String?, b: String?): Int? {
        val left = parse(a) ?: return null
        val right = parse(b) ?: return null
        for (i in 0 until maxOf(left.parts.size, right.parts.size)) {
            val diff = left.parts.getOrElse(i) { 0 } - right.parts.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        if (left.prerelease == right.prerelease) return 0
        if (left.prerelease.isEmpty()) return 1
        if (right.prerelease.isEmpty()) return -1
        return if (left.prerelease < right.prerelease) -1 else 1
    }
}
