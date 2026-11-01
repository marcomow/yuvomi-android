package io.github.marcomow.yuvomi.core

import io.github.marcomow.yuvomi.core.session.VersionGate
import io.github.marcomow.yuvomi.core.session.VersionGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SIGNIN-A9, and upstream's `compareVersions` cases. */
class VersionGateTest {

    @Test fun `older than the minimum blocks`() =
        assertEquals(Verdict.TOO_OLD, VersionGate.verdict("2.82.0", "2.83.0", "2.83.0"))

    @Test fun `the pin is supported`() =
        assertEquals(Verdict.SUPPORTED, VersionGate.verdict("v2.83.0", "2.83.0", "2.83.0"))

    @Test fun `newer than the pin gets a notice`() =
        assertEquals(Verdict.NEWER_THAN_TESTED, VersionGate.verdict("2.90.0", "2.83.0", "2.83.0"))

    @Test fun `unreadable counts as newer`() {
        assertEquals(Verdict.NEWER_THAN_TESTED, VersionGate.verdict(null, "2.83.0", "2.83.0"))
        assertEquals(Verdict.NEWER_THAN_TESTED, VersionGate.verdict("main", "2.83.0", "2.83.0"))
    }

    @Test fun `compare like upstream`() {
        assertEquals(0, VersionGate.compare("1.84", "1.84.0"))
        assertTrue(VersionGate.compare("1.84.0-rc.1", "1.84.0")!! < 0)
        assertTrue(VersionGate.compare("2.10.0", "2.9.9")!! > 0)
        assertNull(VersionGate.compare("x", "1.0"))
    }
}
