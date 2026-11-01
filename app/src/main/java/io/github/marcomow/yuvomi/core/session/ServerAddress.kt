package io.github.marcomow.yuvomi.core.session

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** SIGNIN-R1, SIGNIN-R2. Pure, so it is unit tested without Android. */
object ServerAddress {

    sealed interface Result {
        /** [url] has no trailing slash and no `/api/v1`. [cleartext] means plain http on a home network. */
        data class Ok(val url: String, val cleartext: Boolean) : Result
        data object Invalid : Result
        data object CleartextRefused : Result
    }

    fun normalize(input: String): Result {
        var text = input.trim()
        if (text.isEmpty()) return Result.Invalid
        if (!text.contains("://")) text = "https://$text"
        text = text.trimEnd('/')
        if (text.endsWith("/api/v1", ignoreCase = true)) text = text.dropLast("/api/v1".length).trimEnd('/')

        val uri = runCatching { URI(text) }.getOrNull() ?: return Result.Invalid
        val scheme = uri.scheme?.lowercase() ?: return Result.Invalid
        val host = uri.host ?: return Result.Invalid
        if (scheme != "https" && scheme != "http") return Result.Invalid
        if (uri.query != null || uri.fragment != null) return Result.Invalid

        val normalized = buildString {
            append(scheme).append("://").append(host.lowercase())
            if (uri.port != -1) append(':').append(uri.port)
            uri.rawPath?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { append(it) }
        }
        if (scheme == "http") {
            return if (isPrivateHost(host)) Result.Ok(normalized, cleartext = true) else Result.CleartextRefused
        }
        return Result.Ok(normalized, cleartext = false)
    }

    /**
     * Home-network hosts: `localhost`, `*.local`, and literal addresses in
     * 10/8, 172.16/12, 192.168/16, 100.64/10, 127/8, IPv6 loopback and ULA.
     * Names are not resolved: a public name that resolves privately stays refused.
     */
    fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.trim('[', ']').lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) return true
        val looksLiteral = host.all { it.isDigit() || it == '.' } || host.contains(':')
        if (!looksLiteral) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        val b = address.address.map { it.toInt() and 0xFF }
        if (address is Inet6Address) {
            return address.isLoopbackAddress || (b[0] and 0xFE) == 0xFC
        }
        return b[0] == 10 || b[0] == 127 ||
            (b[0] == 172 && b[1] in 16..31) ||
            (b[0] == 192 && b[1] == 168) ||
            (b[0] == 100 && b[1] in 64..127)
    }
}
