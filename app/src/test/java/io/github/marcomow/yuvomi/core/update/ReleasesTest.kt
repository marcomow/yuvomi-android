package io.github.marcomow.yuvomi.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private fun release(tag: String, draft: Boolean = false, apk: Boolean = true) = Releases.Release(
        tag = tag,
        draft = draft,
        assets = if (apk) listOf(Releases.Asset("yuvomi-android-${tag.removePrefix("v")}.apk", "https://x/$tag.apk", 10, "sha256:ABC")) else emptyList(),
    )

    @Test fun `the newest newer release with an apk wins`() {
        val u = Releases.newest(listOf(release("v0.1.4"), release("v0.1.10"), release("v0.1.9")), "0.1.4")!!
        assertEquals("0.1.10", u.version)
        assertEquals("https://x/v0.1.10.apk", u.apkUrl)
        assertEquals("abc", u.sha256)
    }

    @Test fun `nothing newer means no update`() =
        assertNull(Releases.newest(listOf(release("v0.1.4"), release("v0.1.3")), "0.1.4"))

    @Test fun `drafts, releases without an apk and odd tags are skipped`() =
        assertNull(Releases.newest(listOf(release("v0.2.0", draft = true), release("v0.3.0", apk = false), release("nightly")), "0.1.4"))

    @Test fun `versions compare numerically`() {
        assertTrue(Releases.Version.parse("0.10.0")!! > Releases.Version.parse("0.9.9")!!)
        assertNull(Releases.Version.parse("0.1"))
        assertNull(Releases.Version.parse("0.1.0-beta"))
    }

    @Test fun `the API answer decodes with unknown fields`() {
        val list = Releases.parse(
            """[{"tag_name":"v0.1.4","draft":false,"prerelease":true,"html_url":"https://github.com/r/releases/tag/v0.1.4",
            "assets":[{"name":"yuvomi-android-0.1.4.apk","browser_download_url":"https://d/a.apk","size":3700000,
            "digest":"sha256:01e5","uploader":{"login":"x"}}]}]""",
        )
        assertEquals("01e5", Releases.newest(list, "0.1.3")!!.sha256)
    }
}
