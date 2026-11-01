package io.github.marcomow.yuvomi.core.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Keeps upstream's session cookie (`yuvomi.sid`) and its companions across app
 * starts. [persist] receives the serialized jar and its [generation] whenever a
 * response changes it; the caller encrypts and stores it (ADR 0004). [clear]
 * does not persist: the caller deletes the stored copy itself and drops any
 * write still pending from an older generation.
 */
class SessionCookieJar(
    initial: String? = null,
    private val persist: (serialized: String, generation: Int) -> Unit = { _, _ -> },
) : CookieJar {

    private val cookies = mutableListOf<Cookie>()

    /** Bumped by [clear]; a write carrying an older value is stale. */
    @Volatile var generation = 0
        private set

    init {
        initial?.let { restore(it) }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies += cookie
        }
        persist(serialize(), generation)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
        generation++
    }

    @Synchronized
    fun isEmpty(): Boolean = cookies.isEmpty()

    @Serializable
    private data class Stored(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    )

    @Synchronized
    fun serialize(): String = Json.encodeToString(
        ListSerializer(Stored.serializer()),
        cookies.map { Stored(it.name, it.value, it.domain, it.path, it.expiresAt, it.secure, it.httpOnly, it.hostOnly) },
    )

    private fun restore(text: String) {
        val stored = runCatching { Json.decodeFromString(ListSerializer(Stored.serializer()), text) }.getOrNull() ?: return
        stored.forEach { s ->
            val builder = Cookie.Builder().name(s.name).value(s.value).path(s.path).expiresAt(s.expiresAt)
            if (s.hostOnly) builder.hostOnlyDomain(s.domain) else builder.domain(s.domain)
            if (s.secure) builder.secure()
            if (s.httpOnly) builder.httpOnly()
            cookies += builder.build()
        }
    }
}
