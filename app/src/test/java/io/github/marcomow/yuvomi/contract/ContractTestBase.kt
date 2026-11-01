package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.AuthApi
import io.github.marcomow.yuvomi.core.api.CsrfStore
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.model.LoginOutcome
import io.github.marcomow.yuvomi.core.api.model.SignInPayload
import io.github.marcomow.yuvomi.core.session.SessionCookieJar
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.util.UUID

/**
 * Shared ground for the contract tests: the real API client against upstream's Docker image
 * at the pinned version, seeded with upstream's `scripts/seed-demo.js` (docs/UPSTREAM-SYNC.md).
 * Every test class extending this is skipped unless `YUVOMI_CONTRACT_URL` is set. Seeded
 * accounts: alex and linda (admins), emma and leo (children), all with [PASSWORD]; maria is a
 * housekeeper and cannot sign in.
 */
abstract class ContractTestBase {
    protected val url: String? = System.getProperty("yuvomi.contractUrl")

    @Before fun requireServer() = assumeTrue("YUVOMI_CONTRACT_URL not set", url != null)

    /** A fresh client with its own cookie jar and CSRF store, like the app's. */
    protected fun client(): YuvomiClient {
        val csrf = CsrfStore()
        val http = OkHttpClient.Builder().cookieJar(SessionCookieJar()).addInterceptor(csrf.interceptor).build()
        return YuvomiClient(url!!, http, csrf)
    }

    /** Signs [username] in on a fresh client. */
    protected fun signedIn(username: String): Pair<YuvomiClient, SignInPayload> = runBlocking {
        val c = client()
        val outcome = AuthApi(c).login(username, PASSWORD) as LoginOutcome.SignedIn
        c to outcome.payload
    }

    protected fun unique(prefix: String) = "$prefix ${UUID.randomUUID().toString().take(8)}"

    /** A request outside the app's operations, for fixtures (creating members, cleaning up). */
    protected fun raw(c: YuvomiClient, method: String, path: String, body: String, tolerate: Boolean = false): JsonElement = runBlocking {
        val http = c.javaClass.getDeclaredField("http").apply { isAccessible = true }.get(c) as OkHttpClient
        val request = okhttp3.Request.Builder()
            .url(c.baseUrl.resolve(path)!!)
            .header("X-CSRF-Token", c.csrf.token)
            .method(method, if (method == "GET") null else body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful && !tolerate) error("$method $path -> ${r.code} $text")
            runCatching { ApiJson.parseToJsonElement(text) }.getOrDefault(JsonObject(emptyMap()))
        }
    }

    protected companion object {
        /** Every account `scripts/seed-demo.js` creates uses this password. */
        const val PASSWORD = "demo1234"
    }
}
