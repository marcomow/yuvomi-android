package io.github.marcomow.yuvomi.core.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one HTTP entry point to a household's server (ADR 0003). Knows about
 * upstream's conventions, not about features: CSRF refresh and retry
 * (SIGNIN-R11), session expiry (SIGNIN-R12), idempotency keys (ADR 0007), and
 * the `{ error, code, reason }` error shape.
 */
class YuvomiClient(
    serverUrl: String,
    private val http: OkHttpClient,
    val csrf: CsrfStore,
    private val onSessionExpired: () -> Unit = {},
) {
    val baseUrl: HttpUrl = serverUrl.trimEnd('/').toHttpUrl()

    suspend fun call(
        op: ApiOperation,
        params: Map<String, Any> = emptyMap(),
        query: List<Pair<String, String>> = emptyList(),
        body: JsonElement? = null,
        idempotencyKey: String? = null,
    ): JsonElement = callOnce(op, params, query, body, idempotencyKey, csrfRetry = true)

    private suspend fun callOnce(
        op: ApiOperation,
        params: Map<String, Any>,
        query: List<Pair<String, String>>,
        body: JsonElement?,
        idempotencyKey: String?,
        csrfRetry: Boolean,
    ): JsonElement {
        val url = urlFor(op.path(params), query)
        val requestBody = when {
            body != null -> ApiJson.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON)
            op.stateChanging && op.method != "DELETE" -> "{}".toRequestBody(JSON)
            else -> null
        }
        val request = Request.Builder()
            .url(url)
            .method(op.method, requestBody)
            .header("Accept", "application/json")
            .apply { if (idempotencyKey != null && op.method == "POST") header("Idempotency-Key", idempotencyKey) }
            .build()

        val (status, text) = execute(request)
        val json = text.takeIf { it.isNotBlank() }?.let {
            runCatching { ApiJson.parseToJsonElement(it) }.getOrNull()
        }

        if (status in 200..299) {
            return json ?: if (text.isBlank()) JsonObject(emptyMap()) else throw ApiProblem.Malformed("not JSON: ${op.signature}")
        }

        val error = json as? JsonObject
        val reason = (error?.get("reason") as? JsonPrimitive)?.contentOrNull
        val message = (error?.get("error") as? JsonPrimitive)?.contentOrNull

        if (status == 401 && !op.isSignIn) {
            onSessionExpired()
            throw ApiProblem.SessionExpired()
        }
        if (status == 403 && reason == CSRF_INVALID && csrfRetry && op != Operation.ME) {
            // The PWA does the same: fetch a fresh token through /auth/me, retry once.
            val me = callOnce(Operation.ME, emptyMap(), emptyList(), null, null, csrfRetry = false)
            csrf.update((me.jsonObject["csrfToken"] as? JsonPrimitive)?.contentOrNull)
            return callOnce(op, params, query, body, idempotencyKey, csrfRetry = false)
        }
        throw ApiProblem.Http(status, reason, message, FriendlyError.readParams(error?.get("params") as? JsonObject))
    }

    /** Raw bytes of a same-server resource, e.g. an avatar at `/api/v1/people/3/avatar?v=…`. */
    suspend fun bytes(relativeUrl: String): ByteArray {
        val url = baseUrl.resolve(relativeUrl) ?: throw ApiProblem.Malformed("bad url $relativeUrl")
        require(url.host == baseUrl.host) { "refusing to send the session to another host" }
        val response = await(http.newCall(Request.Builder().url(url).build()))
        response.use {
            if (it.code == 401) {
                onSessionExpired()
                throw ApiProblem.SessionExpired()
            }
            if (!it.isSuccessful) throw ApiProblem.Http(it.code, null, null)
            return readBody { it.body.bytes() }
        }
    }

    /** An absolute URL on the household's server, for "open in browser" (ADR 0006). */
    fun webUrl(path: String): String = baseUrl.resolve(path)?.toString() ?: baseUrl.toString()

    private fun urlFor(path: String, query: List<Pair<String, String>>): HttpUrl {
        val builder = baseUrl.newBuilder().encodedPath(path)
        // Each pair is added on its own: `tag` repeats, never comma-joined (TASKS-A2).
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private suspend fun execute(request: Request): Pair<Int, String> {
        val response = await(http.newCall(request))
        return response.use { it.code to readBody { it.body.string() } }
    }

    /**
     * Reading the body can fail after the headers arrived (a dropped connection, a truncated
     * answer). That is a network failure like any other, not an exception the callers' `ApiProblem`
     * handlers would miss.
     */
    private inline fun <T> readBody(read: () -> T): T =
        try {
            read()
        } catch (e: IOException) {
            throw ApiProblem.Network(e)
        }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ApiProblem.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val CSRF_INVALID = "csrf_invalid"

        fun newIdempotencyKey(): String = UUID.randomUUID().toString()
    }
}
