package io.github.marcomow.yuvomi.core

import io.github.marcomow.yuvomi.core.session.ServerAddress
import io.github.marcomow.yuvomi.core.session.ServerAddress.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SIGNIN-A1, SIGNIN-A2. */
class ServerAddressTest {

    @Test fun `adds https and strips trailing slash`() =
        assertEquals(Result.Ok("https://home.example.org", false), ServerAddress.normalize("home.example.org/"))

    @Test fun `strips api v1`() =
        assertEquals(Result.Ok("https://x.example", false), ServerAddress.normalize("https://x.example/api/v1/"))

    @Test fun `keeps port and sub path`() =
        assertEquals(Result.Ok("https://nas.local:8443/yuvomi", false), ServerAddress.normalize(" https://NAS.local:8443/yuvomi/ "))

    @Test fun `plain http on the home network is allowed with a warning`() =
        assertEquals(Result.Ok("http://192.168.1.5:3000", true), ServerAddress.normalize("http://192.168.1.5:3000"))

    @Test fun `plain http to the internet is refused`() =
        assertEquals(Result.CleartextRefused, ServerAddress.normalize("http://example.org"))

    @Test fun `garbage is invalid`() {
        assertEquals(Result.Invalid, ServerAddress.normalize(""))
        assertEquals(Result.Invalid, ServerAddress.normalize("ftp://x.example"))
    }

    @Test fun `private hosts`() {
        listOf("localhost", "yuvomi.local", "10.0.0.2", "172.16.4.1", "172.31.255.1", "192.168.0.1", "100.64.1.1", "127.0.0.1", "[::1]", "fd00::1")
            .forEach { assertTrue(it, ServerAddress.isPrivateHost(it)) }
        listOf("example.org", "8.8.8.8", "172.32.0.1", "100.128.0.1", "home")
            .forEach { assertFalse(it, ServerAddress.isPrivateHost(it)) }
    }
}
