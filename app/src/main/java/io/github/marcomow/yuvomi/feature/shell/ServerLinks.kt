package io.github.marcomow.yuvomi.feature.shell

import io.github.marcomow.yuvomi.feature.Features
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Links to the member's own Yuvomi server, as the app's own screens (LINKS-R1 to R3). The
 * server is known only at runtime, after sign-in, so nothing about it is in the app or the
 * repository: a link is the server's when its scheme, host and port are the signed-in server's
 * and its path lies under the server's base path. Pure, so it is unit tested.
 */
object ServerLinks {
    /** Marks a deep link that is a shared address, not a route; [MainShell] resolves it. */
    const val SHARED = "shared-link:"

    /** Web app paths (upstream `public/router.js`) whose native screen has the same start route. */
    private val MODULE_PATHS = mapOf(
        "/" to Routes.OVERVIEW, "/tasks" to "tasks", "/rewards/history" to "rewards",
    )

    /** The native route for [url], or null when it is not the server's or the app has no screen for it. */
    fun route(url: String, server: String): String? {
        val link = url.trim().toHttpUrlOrNull() ?: return null
        val base = server.toHttpUrlOrNull() ?: return null
        if (link.scheme != base.scheme || link.host != base.host || link.port != base.port) return null
        val prefix = base.encodedPath.trimEnd('/')
        val path = link.encodedPath.takeIf { it == prefix || it.startsWith("$prefix/") }?.removePrefix(prefix)?.ifEmpty { "/" } ?: return null
        return routeOf(path.trimEnd('/').ifEmpty { "/" }, link)
    }

    private fun routeOf(path: String, link: HttpUrl): String? {
        val open = link.queryParameter("open")?.toIntOrNull()
        return when (path) {
            "/tasks" -> open?.let { Routes.task(it) } ?: "tasks"
            "/calendar" -> open?.let { "calendar?open=$it" } ?: "calendar"
            "/documents" -> open?.let { "documents?open=$it" } ?: "documents"
            "/notes" -> open?.let { "notes/$it" } ?: "notes"
            "/recipes" -> open?.let { "recipes/$it" } ?: "recipes"
            "/shopping" -> link.queryParameter("list")?.toIntOrNull()?.let { "shopping?list=$it" } ?: "shopping"
            else -> when {
                path.startsWith("/health/") -> "health/" + path.removePrefix("/health/").substringBefore('/')
                path.startsWith("/settings") -> "settings"
                else -> MODULE_PATHS[path] ?: Features.all.firstOrNull { "/" + it.id == path }?.startRoute
            }
        }?.let { AppLinks.allowed(it) }
    }

    /** The first http(s) address in shared text (Share → Yuvomi), or null. */
    fun firstUrl(text: String?): String? = text?.let { Regex("""https?://\S+""").find(it)?.value?.trimEnd('.', ',', ')', ']', '>', '"', '\'') }
}
