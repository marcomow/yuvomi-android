package io.github.marcomow.yuvomi.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The app's own releases on GitHub (docs/RELEASING.md, ADR 0018), as `GET
 * /repos/{repo}/releases` lists them. Pure, so the choice of update is unit tested.
 */
object Releases {
    @Serializable
    data class Release(
        @SerialName("tag_name") val tag: String = "",
        val draft: Boolean = false,
        @SerialName("html_url") val page: String? = null,
        val body: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    data class Asset(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
        val size: Long = 0,
        /** `sha256:<hex>`, set by GitHub for every asset uploaded since mid-2025. */
        val digest: String? = null,
    )

    /** An APK newer than the installed version. */
    data class Update(val version: String, val apkUrl: String, val size: Long, val sha256: String?, val page: String?)

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<Release> = json.decodeFromString(ListSerializer(Release.serializer()), body)

    /**
     * The newest published release whose `yuvomi-android-<version>.apk` asset is newer than
     * [installed], or null. Drafts and tags that are not `vMAJOR.MINOR.PATCH` are skipped;
     * prereleases count, because every release of this app is marked as one.
     */
    fun newest(releases: List<Release>, installed: String): Update? {
        val current = Version.parse(installed) ?: return null
        return releases.asSequence()
            .filter { !it.draft }
            .mapNotNull { r ->
                val v = Version.parse(r.tag.removePrefix("v")) ?: return@mapNotNull null
                val apk = r.assets.firstOrNull { it.name == "yuvomi-android-$v.apk" } ?: return@mapNotNull null
                v to Update(v.toString(), apk.url, apk.size, apk.digest?.removePrefix("sha256:")?.lowercase(), r.page)
            }
            .filter { (v, _) -> v > current }
            .maxByOrNull { (v, _) -> v }
            ?.second
    }

    /** `MAJOR.MINOR.PATCH`, as the release workflow tags them. */
    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        override fun toString() = "$major.$minor.$patch"

        companion object {
            private val pattern = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
            fun parse(text: String): Version? = pattern.matchEntire(text.trim())?.destructured?.let { (a, b, c) ->
                runCatching { Version(a.toInt(), b.toInt(), c.toInt()) }.getOrNull()
            }
        }
    }
}
