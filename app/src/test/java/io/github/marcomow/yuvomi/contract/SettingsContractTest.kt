package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.AuthApi
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.settings.ChannelForm
import io.github.marcomow.yuvomi.feature.settings.PermissionDraft
import io.github.marcomow.yuvomi.feature.settings.SettingsApi
import io.github.marcomow.yuvomi.feature.settings.SettingsOperation
import io.github.marcomow.yuvomi.feature.settings.arr
import io.github.marcomow.yuvomi.feature.settings.bool
import io.github.marcomow.yuvomi.feature.settings.child
import io.github.marcomow.yuvomi.feature.settings.int
import io.github.marcomow.yuvomi.feature.settings.obj
import io.github.marcomow.yuvomi.feature.settings.str
import io.github.marcomow.yuvomi.feature.settings.strings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * docs/spec/settings.md against upstream's image at the pinned version, seeded by `seed-demo.js`.
 * Writes that would touch the seeded members run on a member created for the test and
 * removed again; household-wide values are written back unchanged.
 */
class SettingsContractTest : ContractTestBase() {
    private fun api(username: String) = SettingsApi(signedIn(username).first)

    private fun expectHttp(status: Int, reason: String? = null, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected HTTP $status")
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            if (reason != null) assertEquals(reason, e.reason)
        }
    }

    /** A fresh member made by alex through the app's own create call; removed by [cleanup]. */
    private fun freshMember(admin: SettingsApi): Pair<Int, String> = runBlocking {
        val username = "set_" + UUID.randomUUID().toString().take(8)
        val answer = admin.call(SettingsOperation.USER_CREATE, idempotencyKey = YuvomiClient.newIdempotencyKey(), body = buildJsonObject {
            put("username", username)
            put("display_name", unique("Settings"))
            put("password", PASSWORD)
            put("family_role", "relative")
        }).obj()
        val id = answer.child("user").int("id")!!
        id to username
    }

    private fun cleanup(admin: SettingsApi, id: Int) = runBlocking {
        runCatching { admin.call(SettingsOperation.USER_DELETE, mapOf("id" to id)) }
    }

    // ── Account ──

    /** SET-A20: own profile and password. */
    @Test fun `SET-A20 profile and password change on a fresh member`() = runBlocking {
        val alex = api("alex")
        val (id, username) = freshMember(alex)
        try {
            val me = api(username)
            val saved = me.call(SettingsOperation.PROFILE_UPDATE, body = buildJsonObject {
                put("display_name", "Renamed")
                put("avatar_color", "#16A34A")
                put("phone", "+49 30 1234")
            }).obj().child("user")
            assertEquals("Renamed", saved.str("display_name"))
            assertEquals("#16A34A", saved.str("avatar_color"))
            expectHttp(400, "wrong_password") {
                me.call(SettingsOperation.PASSWORD_CHANGE, body = buildJsonObject { put("current_password", "nope-nope"); put("new_password", "another pass") })
            }
            assertTrue(me.call(SettingsOperation.PASSWORD_CHANGE, body = buildJsonObject { put("current_password", PASSWORD); put("new_password", PASSWORD) }).obj().bool("ok"))
        } finally {
            cleanup(alex, id)
        }
    }

    /** SET-A21: the Gravatar route answers with a reason, whatever the operator set. */
    @Test fun `SET-A21 gravatar answers a known reason`() = runBlocking {
        try {
            api("alex").data(SettingsOperation.GRAVATAR, body = buildJsonObject { put("email", "alex@example.com") })
        } catch (e: ApiProblem.Http) {
            assertTrue(e.reason, e.reason in setOf("gravatar_disabled", "no_email", "gravatar_stale", "gravatar_not_found", "gravatar_unreachable"))
        }
        Unit
    }

    /** SET-A22: the whole second-factor flow, on a member made for it. */
    @Test fun `SET-A22 two-factor setup, recovery codes and turning it off`() = runBlocking {
        val alex = api("alex")
        val (id, username) = freshMember(alex)
        try {
            val me = api(username)
            assertFalse(me.data(SettingsOperation.TWO_FACTOR_STATUS).obj().bool("enabled"))
            val setup = me.data(SettingsOperation.TWO_FACTOR_SETUP).obj()
            val secret = setup.str("secret")!!
            assertTrue(setup.str("uri")!!.startsWith("otpauth://"))
            expectHttp(400, "invalid_code") { me.data(SettingsOperation.TWO_FACTOR_ENABLE, body = buildJsonObject { put("code", "000000x") }) }
            val codes = me.data(SettingsOperation.TWO_FACTOR_ENABLE, body = buildJsonObject { put("code", totp(secret, 0)) }).obj().strings("recovery_codes")
            assertTrue(codes.size >= 8)
            val status = me.data(SettingsOperation.TWO_FACTOR_STATUS).obj()
            assertTrue(status.bool("enabled"))
            assertEquals(codes.size, status.int("recovery_remaining"))
            val fresh = me.data(SettingsOperation.TWO_FACTOR_RECOVERY, body = buildJsonObject { put("code", codes[0]) }).obj().strings("recovery_codes")
            assertTrue(fresh.isNotEmpty() && fresh[0] != codes[0])
            assertFalse(me.data(SettingsOperation.TWO_FACTOR_DISABLE, body = buildJsonObject { put("code", fresh[0]) }).obj().bool("enabled"))
            expectHttp(409, "not_enabled") { me.data(SettingsOperation.TWO_FACTOR_DISABLE, body = buildJsonObject { put("code", "123456") }) }
        } finally {
            cleanup(alex, id)
        }
    }

    /** SET-A23: other sessions end; this one stays. */
    @Test fun `SET-A23 logout-others ends the member's other sessions`() = runBlocking {
        val alex = api("alex")
        val (id, username) = freshMember(alex)
        try {
            val first = signedIn(username).first
            val second = api(username)
            assertTrue((second.call(SettingsOperation.LOGOUT_OTHERS).obj().int("ended") ?: 0) >= 1)
            assertEquals(username, second.me().child("user").str("username"))
            try {
                AuthApi(first).me()
                fail("the first session should have ended")
            } catch (e: ApiProblem.SessionExpired) {
                // expected
            }
        } finally {
            cleanup(alex, id)
        }
    }

    /** SET-A24: single sign-on state without a provider. */
    @Test fun `SET-A24 the SSO link reads and refuses an unlink that has nothing to unlink`() = runBlocking {
        val alex = api("alex")
        val link = alex.call(SettingsOperation.OIDC_LINK).obj()
        assertFalse(link.bool("linked"))
        assertTrue("enabled" in link)
        expectHttp(409) { alex.call(SettingsOperation.OIDC_UNLINK) }
        val config = alex.call(SettingsOperation.OIDC_CONFIG).obj()
        assertTrue("enabled" in config)
    }

    // ── Preferences ──

    /** SET-A25: a patch answers the whole set; region and zone are admin fields. */
    @Test fun `SET-A25 PUT preferences answers the whole set and guards household fields`() = runBlocking {
        val alex = api("alex")
        val before = alex.preferences()
        val saved = alex.savePreferences(buildJsonObject { put("date_format", before.str("date_format")!!) })
        assertEquals(before.str("date_format"), saved.str("date_format"))
        assertTrue("timezone_effective" in saved && "disabled_modules" in saved && "module_order" in saved)
        expectHttp(403) { api("emma").savePreferences(buildJsonObject { put("timezone", "Europe/Berlin") }) }
        expectHttp(400) { alex.savePreferences(buildJsonObject { put("date_format", "nope") }) }
        // Per member: my navigation order is mine.
        val order = before.strings("module_order")
        assertEquals(order, alex.savePreferences(buildJsonObject { put("module_order", JsonArray(order.map(::JsonPrimitive))) }).strings("module_order"))
    }

    // ── Family ──

    /** SET-A26: create, edit, list, delete a member. */
    @Test fun `SET-A26 members are created, edited and removed`() = runBlocking {
        val alex = api("alex")
        val (id, username) = freshMember(alex)
        try {
            assertTrue(alex.data(SettingsOperation.USERS).arr().any { it.obj().str("username") == username })
            val edited = alex.call(SettingsOperation.USER_UPDATE, mapOf("id" to id), body = buildJsonObject {
                put("display_name", "Edited")
                put("family_role", "child")
                put("system_admin", false)
            }).obj().child("user")
            assertEquals("Edited", edited.str("display_name"))
            assertEquals("child", edited.str("family_role"))
        } finally {
            val outcome = alex.call(SettingsOperation.USER_DELETE, mapOf("id" to id)).obj().str("outcome")
            assertEquals("deleted", outcome)
        }
        expectHttp(403) { api("emma").call(SettingsOperation.USER_DELETE, mapOf("id" to 1)) }
    }

    /** SET-A27: the order is written as a whole. */
    @Test fun `SET-A27 the member order is written as it is`() = runBlocking {
        val c = signedIn("alex").first
        val ids = raw(c, "GET", "/api/v1/family/members", "").obj()["data"].arr().mapNotNull { it.obj().int("id") }
        val answer = SettingsApi(c).data(SettingsOperation.FAMILY_REORDER, body = buildJsonObject { put("order", JsonArray(ids.map(::JsonPrimitive))) })
        assertEquals(ids, answer.arr().mapNotNull { it.obj().int("id") })
        expectHttp(403, "admin_required") { api("emma").data(SettingsOperation.FAMILY_REORDER, body = buildJsonObject { put("order", JsonArray(ids.map(::JsonPrimitive))) }) }
    }

    /** SET-A28: an invitation is created with a one-time token and revoked. */
    @Test fun `SET-A28 invitations are created, listed and revoked`() = runBlocking {
        val alex = api("alex")
        val created = alex.data(SettingsOperation.INVITE_CREATE, idempotencyKey = YuvomiClient.newIdempotencyKey(), body = buildJsonObject {
            put("display_name", unique("Invitee"))
            put("family_role", "relative")
            put("permission_preset", "restricted")
        }).obj()
        assertFalse(created.str("token").isNullOrEmpty())
        val id = created.child("invite").int("id")!!
        assertTrue(alex.data(SettingsOperation.INVITES).obj()["invites"].arr().any { it.obj().int("id") == id })
        alex.data(SettingsOperation.INVITE_REVOKE, mapOf("id" to id))
        expectHttp(404) { alex.data(SettingsOperation.INVITE_REVOKE, mapOf("id" to id)) }
    }

    /** SET-A29: who has a second factor; the requirement is written back unchanged. */
    @Test fun `SET-A29 the household second factor overview and requirement`() = runBlocking {
        val alex = api("alex")
        val overview = alex.call(SettingsOperation.TWO_FACTOR_OVERVIEW).obj()
        assertTrue(overview["data"].arr().all { "display_name" in it.obj() && "enabled" in it.obj() })
        val required = overview.bool("required")
        assertEquals(required, alex.data(SettingsOperation.TWO_FACTOR_REQUIRE, body = buildJsonObject { put("required", required) }).obj().bool("required"))
        expectHttp(403) { api("emma").call(SettingsOperation.TWO_FACTOR_OVERVIEW) }
    }

    // ── Permissions ──

    /** SET-A30: catalogue, role profile, member exceptions. */
    @Test fun `SET-A30 role and member permissions read and write sparse maps`() = runBlocking {
        val alex = api("alex")
        val catalog = alex.data(SettingsOperation.PERMISSIONS_CATALOG).obj()
        assertTrue(catalog["modules"].arr().any { it.obj().str("key") == "calendar" })
        assertTrue("child" in catalog.strings("roles"))
        assertTrue(catalog["members"].arr().isNotEmpty())
        val role = alex.data(SettingsOperation.PERMISSIONS_ROLE, mapOf("familyRole" to "relative")).obj()
        val original = PermissionDraft.of(role)
        try {
            val written = alex.data(SettingsOperation.PERMISSIONS_ROLE_UPDATE, mapOf("familyRole" to "relative"), body = original.with("modules", "budget", "read").payload()).obj()
            assertEquals("read", written.child("modules").str("budget"))
        } finally {
            alex.data(SettingsOperation.PERMISSIONS_ROLE_UPDATE, mapOf("familyRole" to "relative"), body = original.payload())
        }
        val (id, _) = freshMember(alex)
        try {
            assertTrue(alex.data(SettingsOperation.PERMISSIONS_USER, mapOf("userId" to id)).obj().child("modules").isEmpty())
            val mine = alex.data(SettingsOperation.PERMISSIONS_USER_UPDATE, mapOf("userId" to id), body = PermissionDraft(modules = mapOf("notes" to "none", "tasks" to "write")).payload()).obj()
            // The default (write) is not stored.
            assertEquals(mapOf("notes" to JsonPrimitive("none")), mine.child("modules"))
        } finally {
            cleanup(alex, id)
        }
        expectHttp(400) { alex.data(SettingsOperation.PERMISSIONS_USER_UPDATE, mapOf("userId" to signedIn("alex").second.user.id), body = PermissionDraft().payload()) }
    }

    // ── Modules ──

    /** SET-A31: installed modules list; a module that is not there answers 404. */
    @Test fun `SET-A31 modules list and an unknown module`() = runBlocking {
        val alex = api("alex")
        assertTrue(alex.data(SettingsOperation.MODULES, query = listOf("admin" to "1")) is JsonArray)
        try {
            alex.data(SettingsOperation.MODULE_UPDATE, mapOf("id" to "no-such-module"), body = buildJsonObject { put("enabled", false) })
            fail("expected an error")
        } catch (e: ApiProblem.Http) {
            assertTrue(e.status in 400..404)
        }
    }

    // ── Notification channels ──

    /** SET-A32: a channel is created, changed, tested and deleted. */
    @Test fun `SET-A32 notification channels round trip`() = runBlocking {
        val alex = api("alex")
        assertTrue(alex.data(SettingsOperation.NOTIFICATION_PROVIDERS).arr().any { it.obj().str("id") == "gotify" })
        val form = ChannelForm(provider = "webhook", name = unique("Hook"), baseUrl = "https://hooks.example.invalid/yuvomi")
        val created = alex.data(SettingsOperation.NOTIFICATION_CHANNEL_CREATE, body = form.body(), idempotencyKey = YuvomiClient.newIdempotencyKey()).obj()
        val id = created.int("id")!!
        try {
            assertEquals("webhook", created.str("provider"))
            assertTrue(alex.data(SettingsOperation.NOTIFICATION_CHANNELS).arr().any { it.obj().int("id") == id })
            val renamed = alex.data(SettingsOperation.NOTIFICATION_CHANNEL_UPDATE, mapOf("id" to id), body = form.copy(name = "Renamed").body()).obj()
            assertEquals("Renamed", renamed.str("name"))
            try {
                alex.data(SettingsOperation.NOTIFICATION_CHANNEL_TEST, mapOf("id" to id), idempotencyKey = YuvomiClient.newIdempotencyKey())
            } catch (e: ApiProblem.Http) {
                assertEquals(500, e.status) // the address does not exist; the failure is recorded on the channel
            }
            expectHttp(400) { alex.data(SettingsOperation.NOTIFICATION_CHANNEL_CREATE, body = ChannelForm(provider = "ntfy").body()) }
        } finally {
            assertTrue(alex.data(SettingsOperation.NOTIFICATION_CHANNEL_DELETE, mapOf("id" to id)).obj().bool("deleted"))
        }
        expectHttp(403) { api("emma").data(SettingsOperation.NOTIFICATION_CHANNELS) }
    }

    // ── E-mail, backup, API tokens ──

    /** SET-A33: SMTP config refuses a bad port by reason and the test names its case. */
    @Test fun `SET-A33 email config and test`() = runBlocking {
        val alex = api("alex")
        val cfg = alex.data(SettingsOperation.EMAIL_CONFIG).obj()
        assertTrue("port" in cfg && "envControlled" in cfg && "passwordSet" in cfg)
        expectHttp(400, "email_port_invalid") { alex.data(SettingsOperation.EMAIL_CONFIG_UPDATE, body = buildJsonObject { put("port", 0) }) }
        assertEquals(cfg.str("host"), alex.data(SettingsOperation.EMAIL_CONFIG_UPDATE, body = buildJsonObject {}).obj().str("host"))
        try {
            val result = alex.data(SettingsOperation.EMAIL_TEST, idempotencyKey = YuvomiClient.newIdempotencyKey()).obj()
            assertTrue(result.bool("ok") || result.str("reason") != null)
        } catch (e: ApiProblem.Http) {
            assertEquals("email_no_recipient", e.reason)
        }
    }

    /** SET-A34: backup status, download, run now, WebDAV settings. */
    @Test fun `SET-A34 backup status, download and WebDAV`() = runBlocking {
        val alex = api("alex")
        val status = alex.data(SettingsOperation.BACKUP_STATUS).obj()
        assertNotNull(status.int("schema_version"))
        assertTrue("scheduler" in status)
        val bytes = alex.bytes(SettingsOperation.BACKUP_DATABASE)
        assertEquals("SQLite format 3", String(bytes, 0, 15))
        try {
            assertTrue("timestamp" in alex.data(SettingsOperation.BACKUP_TRIGGER, idempotencyKey = YuvomiClient.newIdempotencyKey()).obj())
        } catch (e: ApiProblem.Http) {
            assertEquals(500, e.status) // no writable backup directory in this container
        }
        val webdav = alex.data(SettingsOperation.WEBDAV_CONFIG).obj()
        assertTrue("keep" in webdav && "configured" in webdav)
        assertEquals(webdav.bool("enabled"), alex.data(SettingsOperation.WEBDAV_CONFIG_UPDATE, body = buildJsonObject { put("enabled", webdav.bool("enabled")) }).obj().bool("enabled"))
        expectHttp(400) { alex.data(SettingsOperation.WEBDAV_CONFIG_UPDATE, body = buildJsonObject { put("url", "ftp://nope") }) }
        expectHttp(400) { alex.data(SettingsOperation.WEBDAV_TEST, body = buildJsonObject { put("url", "https://webdav.example.invalid/") ; put("username", "x"); put("password", "y") }, idempotencyKey = YuvomiClient.newIdempotencyKey()) }
        if (!webdav.bool("configured")) expectHttp(500) { alex.data(SettingsOperation.WEBDAV_TRIGGER, idempotencyKey = YuvomiClient.newIdempotencyKey()) }
        expectHttp(403) { api("emma").data(SettingsOperation.BACKUP_STATUS) }
    }

    /** SET-A35: a token is created once in clear, then revoked and removed. */
    @Test fun `SET-A35 API tokens are created, revoked and removed`() = runBlocking {
        val alex = api("alex")
        val created = alex.call(SettingsOperation.API_TOKEN_CREATE, idempotencyKey = YuvomiClient.newIdempotencyKey(), body = buildJsonObject {
            put("name", unique("Token"))
            put("scopes", JsonArray(listOf(JsonPrimitive("tasks:read"))))
        }).obj()
        assertFalse(created.str("token").isNullOrEmpty())
        val id = created.child("data").int("id")!!
        val list = alex.call(SettingsOperation.API_TOKENS).obj()
        assertTrue(list["data"].arr().any { it.obj().int("id") == id })
        assertTrue("subjects" in list)
        alex.call(SettingsOperation.API_TOKEN_REVOKE, mapOf("id" to id))
        alex.call(SettingsOperation.API_TOKEN_REMOVE, mapOf("id" to id), idempotencyKey = YuvomiClient.newIdempotencyKey())
        assertFalse(alex.call(SettingsOperation.API_TOKENS).obj()["data"].arr().any { it.obj().int("id") == id })
        expectHttp(400) { alex.call(SettingsOperation.API_TOKEN_CREATE, body = buildJsonObject { put("name", "") }) }
    }

    // ── Module sheets ──

    /** SET-A36: sync targets for the task and event defaults. */
    @Test fun `SET-A36 sync targets read as lists`() = runBlocking {
        val alex = api("alex")
        assertTrue(alex.data(SettingsOperation.TASKS_SYNC_TARGETS).obj()["caldav"] is JsonArray)
        val calendar = alex.data(SettingsOperation.CALENDAR_SYNC_TARGETS).obj()
        assertTrue(calendar["google"] is JsonArray && calendar["caldav"] is JsonArray)
    }

    /** SET-A37: default points: how many open tasks, and a rebase that changes nothing. */
    @Test fun `SET-A37 default points affected and rebase`() = runBlocking {
        val alex = api("alex")
        assertNotNull(alex.data(SettingsOperation.TASKS_POINTS_AFFECTED, query = listOf("points" to "5")).obj().int("count"))
        assertEquals(0, alex.data(SettingsOperation.TASKS_POINTS_REBASE, body = buildJsonObject { put("from", 5); put("to", 5) }).obj().int("updated"))
        expectHttp(400) { alex.data(SettingsOperation.TASKS_POINTS_REBASE, body = buildJsonObject { put("from", 0); put("to", 5) }) }
        expectHttp(403) { api("emma").data(SettingsOperation.TASKS_POINTS_AFFECTED, query = listOf("points" to "5")) }
    }

    /** SET-A38: the place search refuses a one-letter name without asking the weather service. */
    @Test fun `SET-A38 geocode refuses a too short name`() {
        expectHttp(400, "name_too_short") { api("alex").data(SettingsOperation.WEATHER_GEOCODE, query = listOf("name" to "a")) }
    }

    /** SET-A39: my weather place is mine and resets to the household's. */
    @Test fun `SET-A39 the personal weather place saves and resets`() = runBlocking {
        val alex = api("alex")
        val (id, username) = freshMember(alex)
        try {
            val me = api(username)
            val saved = me.savePreferences(buildJsonObject {
                putJsonObject("weather_user") { put("lat", 52.52); put("lon", 13.405); put("city", "Berlin"); put("units", "metric"); put("auto_locate", false) }
            })
            assertEquals("Berlin", saved.child("weather_user").str("city"))
            val reset = me.savePreferences(buildJsonObject {
                putJsonObject("weather_user") { put("lat", null as String?); put("lon", null as String?); put("city", null as String?); put("units", null as String?); put("auto_locate", null as Boolean?) }
            })
            assertEquals(null, reset.child("weather_user").str("lat"))
        } finally {
            cleanup(alex, id)
        }
    }

    /** SET-A40: the fixtures leave nothing behind: a removed member cannot sign in. */
    @Test fun `SET-A40 a removed member cannot sign in`() = runBlocking {
        val alex = api("alex")
        val (id, username) = freshMember(alex)
        cleanup(alex, id)
        try {
            val outcome = AuthApi(client()).login(username, PASSWORD)
            fail("signed in: $outcome")
        } catch (e: ApiProblem.Http) {
            assertEquals(401, e.status)
        }
        Unit
    }

    private fun totp(base32: String, stepOffset: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in base32.uppercase().filter { it != '=' }) {
            value = (value shl 5) or alphabet.indexOf(ch); bits += 5
            if (bits >= 8) { out.write((value ushr (bits - 8)) and 0xFF); bits -= 8 }
        }
        val step = System.currentTimeMillis() / 30_000 + stepOffset
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(out.toByteArray(), "HmacSHA1")) }
        val hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array())
        val o = hash.last().toInt() and 0x0F
        val bin = ((hash[o].toInt() and 0x7F) shl 24) or ((hash[o + 1].toInt() and 0xFF) shl 16) or
            ((hash[o + 2].toInt() and 0xFF) shl 8) or (hash[o + 3].toInt() and 0xFF)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }
}
