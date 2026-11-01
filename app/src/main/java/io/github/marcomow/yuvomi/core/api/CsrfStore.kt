package io.github.marcomow.yuvomi.core.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * The CSRF token of the current session, in memory only (ADR 0004). Upstream sends
 * the current token in the `X-CSRF-Token` header of every `/api/v1` response to a
 * session and in `csrfToken` of the sign-in payload; state-changing requests send
 * it back in the same header (SIGNIN-R11).
 */
class CsrfStore {
    @Volatile
    var token: String = ""
        private set

    fun update(value: String?) {
        if (!value.isNullOrBlank()) token = value
    }

    fun clear() {
        token = ""
    }

    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        val outgoing = if (request.method != "GET" && request.method != "HEAD" && token.isNotEmpty()) {
            request.newBuilder().header(HEADER, token).build()
        } else {
            request
        }
        val response: Response = chain.proceed(outgoing)
        update(response.header(HEADER))
        response
    }

    companion object {
        const val HEADER = "X-CSRF-Token"
    }
}
