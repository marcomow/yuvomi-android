package io.github.marcomow.yuvomi.android

import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SIGNIN-A12 with the real assets and ICU plural rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class)
class StringsLoaderTest {
    private val assets = ApplicationProvider.getApplicationContext<YuvomiApp>().assets

    @Test fun `polish uses one, few and many`() {
        val pl = StringsLoader.load(assets, "pl")
        assertEquals("Co 1 tydzień", pl.t("common.everyNWeeks", "count" to 1))
        assertEquals("Co 3 tygodnie", pl.t("common.everyNWeeks", "count" to 3))
        assertEquals("Co 5 tygodni", pl.t("common.everyNWeeks", "count" to 5))
    }

    @Test fun `missing keys fall back to English, app keys load too`() {
        val de = StringsLoader.load(assets, "de")
        assertEquals("Aufgaben", de.t("tasks.title"))
        assertEquals("Unofficial Android client", de.t("android.signin.unofficial"))
        assertTrue(StringsLoader.load(assets, "ar").isRtl)
    }
}
