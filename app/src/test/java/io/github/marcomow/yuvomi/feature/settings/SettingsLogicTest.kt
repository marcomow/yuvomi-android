package io.github.marcomow.yuvomi.feature.settings

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** docs/spec/settings.md, the pure rules (SET-A1 to A12). */
class SettingsLogicTest {
    private fun obj(json: String): JsonObject = ApiJson.parseToJsonElement(json).jsonObject
    private val admin = SettingsRegistry.Viewer(true) { "write" }
    private val member = SettingsRegistry.Viewer(false) { if (it == "health") "none" else "write" }

    // ── Registry (SET-A1, A2) ──

    @Test fun `SET-A1 a member sees Account and Modules but not Household`() {
        assertEquals(listOf("personal", "admin", "modules"), SettingsRegistry.domainsFor(admin).map { it.id })
        assertEquals(listOf("personal", "modules"), SettingsRegistry.domainsFor(member).map { it.id })
        val modules = SettingsRegistry.leavesOf("modules", member).map { it.id }
        // Admin-only sheets have no section for a member; the health sheet follows `health: none`.
        assertFalse("modules-active" in modules)
        assertFalse("module-health" in modules)
        assertFalse("module-kitchen" in modules)
        assertTrue("modules-navigation" in modules)
        assertTrue("module-tasks" in modules)
        // An admin is never restricted by module access.
        assertTrue("module-health" in SettingsRegistry.leavesOf("modules", SettingsRegistry.Viewer(true) { "none" }).map { it.id })
    }

    @Test fun `SET-A2 sections come mine first, module sheets in navigation order, install last`() {
        val tasks = SettingsRegistry.leaf("module-tasks")!!
        assertEquals(listOf("personal-tasks", "options-tasks", "sync-reminders"), SettingsRegistry.sectionsOf(tasks, admin).map { it.id })
        assertEquals(listOf("personal-tasks"), SettingsRegistry.sectionsOf(tasks, member).map { it.id })
        val sheets = SettingsRegistry.leavesOf("modules", admin).map { it.id }
        assertEquals(listOf("modules-active", "modules-navigation", "module-dashboard"), sheets.take(3))
        assertEquals("modules-install", sheets.last())
        // Plan before household before people before finance; the member's order inside a group.
        assertTrue(sheets.indexOf("module-tasks") < sheets.indexOf("module-kitchen"))
        assertTrue(sheets.indexOf("module-health") < sheets.indexOf("module-budget"))
        val reordered = SettingsRegistry.leavesOf("modules", admin, listOf("schedule", "tasks")).map { it.id }
        assertTrue(reordered.indexOf("module-schedule") < reordered.indexOf("module-tasks"))
        assertEquals("/settings/modules/tasks?section=sync-reminders", SettingsRegistry.webPath(SettingsRegistry.sections.first { it.id == "sync-reminders" }))
    }

    @Test fun `SET-A3 search ignores case and accents and ranks label starts first`() {
        val t = mapOf("settings.pageAccount" to "Konto", "settings.pageWeather" to "Wetter für mich").let { m -> { k: String -> m[k] ?: k } }
        val hits = SettingsSearch.search("WETTER", admin, t)
        assertEquals("personal-weather", hits.first().leaf.id)
        assertEquals("fur", SettingsSearch.normalize("Für"))
        assertTrue(SettingsSearch.search("  ", admin, t).isEmpty())
    }

    // ── Module order (SET-A4) ──

    @Test fun `SET-A4 module order groups the kitchen and fills the phone bar`() {
        assertEquals(listOf("kitchen", "tasks"), ModuleOrder.normalize(listOf("meals", "tasks", "pantry", "kitchen")))
        val sorted = ModuleOrder.sort(listOf("budget", "settings", "tasks", "dashboard", "meals", "calendar"), listOf("calendar", "tasks")) { it }
        assertEquals(listOf("dashboard", "calendar", "tasks", "meals", "budget", "settings"), sorted)
        assertEquals(listOf("calendar", "tasks", "kitchen"), ModuleOrder.resolveMobile(emptyList(), listOf("tasks", "calendar", "meals", "notes")))
        assertEquals(listOf("notes", "calendar", "tasks"), ModuleOrder.resolveMobile(listOf("notes", "dashboard"), listOf("tasks", "calendar", "notes")))
    }

    @Test fun `SET-A5 module rows put the kitchen before housekeeping and switch its children together`() {
        val prefs = obj("""{"disabled_modules":["pantry","budget"],"hidden_modules":["notes"],"module_order":[]}""")
        val rows = ModuleRules.rows(prefs, listOf(obj("""{"id":"lego","name":"Lego","enabled":true,"status":"enabled"}""")), includeLocked = false)
        val ids = rows.map { it.id }
        // `buildRows`: before the first of the later modules, which is rewards in file order.
        assertEquals("rewards", ids[ids.indexOf("kitchen") + 1])
        assertFalse("dashboard" in ids)
        assertEquals("third-party-lego", rows.last().orderId)
        assertFalse(rows.first { it.id == "budget" }.enabled)
        assertTrue(rows.first { it.id == "kitchen" }.enabled)
        assertTrue(rows.first { it.id == "notes" }.hidden)
        assertEquals(setOf("budget", "meals", "recipes", "shopping", "pantry"), ModuleRules.disabledAfter(prefs, "kitchen", false).toSet())
        assertEquals(listOf("budget"), ModuleRules.disabledAfter(prefs, "kitchen", true))
        assertEquals(setOf("notes", "tasks"), ModuleRules.hiddenAfter(prefs, "tasks", false).toSet())
        val all = ModuleOrder.sort(ModuleRules.rows(prefs, emptyList(), true), emptyList()) { it.orderId }
        val moved = ModuleRules.moved(all, "tasks", -1, emptyList())
        assertTrue(moved.indexOf("tasks") < moved.indexOf("calendar"))
        assertTrue(moved.containsAll(ModuleOrder.KITCHEN_CHILDREN))
        // A step across a group border changes nothing.
        assertEquals(ModuleRules.expand(all.filterNot { it.locked }.map { it.orderId }), ModuleRules.moved(all, "calendar", -1, emptyList()))
    }

    // ── Account (SET-A6) ──

    @Test fun `SET-A6 account rules follow personal-account`() {
        assertEquals(mapOf("current" to "common.required", "next" to "settings.passwordTooShort", "confirm" to "settings.passwordMismatch"),
            AccountRules.passwordProblems("", "short", "other"))
        assertTrue(AccountRules.passwordProblems("old", "long enough", "long enough").isEmpty())
        val today = LocalDate.of(2026, 11, 2)
        assertEquals(setOf("display_name", "email", "birth_date"), AccountRules.profileProblems(" ", "a@b@c", "2030-01-01", today).keys)
        assertTrue(AccountRules.profileProblems("Alex", "", "", today).isEmpty())
        assertTrue(AccountRules.hasSavedEmail("alex@example.com"))
        assertFalse(AccountRules.hasSavedEmail("a@b, c@d"))
        assertEquals("JBSW Y3DP EHPK 3PXP", AccountRules.groupSecret("JBSWY3DPEHPK3PXP"))
        assertEquals("settings.twoFactorInvalidCode", AccountRules.twoFactorErrorKey(ApiProblem.Http(400, "invalid_code", "x")))
        assertEquals("settings.twoFactorTooManyAttempts", AccountRules.twoFactorErrorKey(ApiProblem.Http(429, null, null)))
        assertEquals("settings.gravatarErrorNotFound", AccountRules.gravatarErrorKey(ApiProblem.Http(404, "gravatar_not_found", null)))
        assertEquals("settings.otherSessionsTooManyAttempts", AccountRules.logoutOthersErrorKey(ApiProblem.Http(429, null, null)))
        assertEquals("common.emailInUse", SettingsErrors.key(ApiProblem.Http(409, "email_in_use", "English")))
        assertEquals("common.errorNoPermission", SettingsErrors.key(ApiProblem.Http(403, null, "Admin access required.")))
        assertEquals("settings.preferenceSaveFailed", SettingsErrors.key(ApiProblem.Http(400, null, "Invalid"), "settings.preferenceSaveFailed"))
    }

    // ── Region (SET-A7) ──

    @Test fun `SET-A7 region resolves the saved preset while its triple matches`() {
        assertEquals("de-DE", RegionRules.detect("EUR", "dmy", "24h"))
        assertEquals("de-AT", RegionRules.resolve("de-AT", "EUR", "dmy", "24h"))
        assertEquals("nl-NL", RegionRules.resolve("nl-NL", "EUR", "dmy", "24h"))
        // A saved region whose triple changed falls back to detection.
        assertEquals("de-CH", RegionRules.resolve("de-DE", "CHF", "dmy", "24h"))
        assertEquals(RegionRules.CUSTOM, RegionRules.detect("EUR", "mdy", "12h"))
        assertTrue(RegionRules.keepsLanguage("de-CH"))
        assertTrue(RegionRules.keepsLanguage("en-CA"))
        assertFalse(RegionRules.keepsLanguage("ja-JP"))
        assertEquals("Canada (French)", RegionRules.label("fr-CA", Locale.ENGLISH))
        assertEquals("EUR - Euro", RegionRules.currencyLabel("EUR", Locale.ENGLISH))
        assertEquals("UTC", RegionRules.zones().first())
        assertTrue("Europe/Berlin" in RegionRules.zones())
        assertEquals(61, RegionRules.CURRENCIES.size)
    }

    // ── Module options (SET-A8) ──

    @Test fun `SET-A8 option fields take what the server takes`() {
        assertEquals(0, OptionRules.graceDays("0"))
        assertNull(OptionRules.graceDays("91"))
        assertNull(OptionRules.graceDays(""))
        assertEquals(10000, OptionRules.points("10000"))
        assertNull(OptionRules.points("10001"))
        val targets = obj("""{"google":[{"id":"g1","summary":"Family"}],"caldav":[{"accountId":2,"accountName":"Nextcloud","calendarUrl":"https://x/c","calendarName":"Home"}]}""")
        val options = OptionRules.calendarTargets(targets, "caldav:9|gone", "Local", "Google", "CalDAV", "Unavailable")
        assertEquals(listOf("", "google:g1", "caldav:2|https://x/c", "caldav:9|gone"), options.map { it.first })
        assertEquals("Unavailable", options.last().second)
    }

    // ── Family (SET-A9) ──

    @Test fun `SET-A9 family rules order members and check the form`() {
        assertEquals("settings.familyRoleGrandparent", FamilyRules.roleKey("grandparent"))
        val users = listOf(
            obj("""{"id":1,"is_household_member":true}"""), obj("""{"id":2,"is_household_member":true,"deactivated_at":"2026-01-01"}"""),
            obj("""{"id":3,"is_household_member":true}"""), obj("""{"id":4,"is_worker":true,"is_household_member":false}"""),
        )
        assertEquals(listOf(1, 3), FamilyRules.orderable(users))
        assertEquals(listOf(1, 3, 4, 2), FamilyRules.ordered(users).map { it.int("id") })
        assertEquals(listOf(3, 1), FamilyRules.moved(listOf(1, 3), 3, -1))
        assertEquals(listOf(1, 3), FamilyRules.moved(listOf(1, 3), 1, -1))
        val today = LocalDate.of(2026, 11, 2)
        assertEquals(setOf("username", "password"), FamilyRules.memberProblems("a b", "Kid", "", true, false, "", "", today).keys)
        assertTrue(FamilyRules.memberProblems("kid.one", "Kid", "", false, false, "", "", today).isEmpty())
        assertTrue(FamilyRules.memberProblems("kid.one", "Kid", "", true, true, "", "", today).isEmpty())
        assertEquals("Open", FamilyRules.invitePrimary(obj("""{"email":"x@y.z","display_name":"Open"}""")))
        assertNull(FamilyRules.invitePrimary(obj("""{"id":1}""")))
    }

    // ── Permissions (SET-A10) ──

    @Test fun `SET-A10 a permission save sends only own entries`() {
        val draft = PermissionDraft.of(obj("""{"modules":{"budget":"none"},"widgets":{},"capabilities":{"notes_edit_others":"none"}}"""))
            .with("modules", "health", PermissionDraft.INHERIT).with("widgets", "clock", "none")
        assertEquals("""{"modules":{"budget":"none"},"widgets":{"clock":"none"},"capabilities":{"notes_edit_others":"none"}}""", draft.payload().toString())
        assertEquals("""{"modules":{},"widgets":{},"capabilities":{}}""", PermissionDraft().payload().toString())
        assertEquals("allow", PermissionRules.capability("allow", "role", null, null))
        assertEquals("none", PermissionRules.capability("allow", "user", PermissionDraft.INHERIT, "none"))
        assertEquals("read", PermissionRules.module("user", null, "read"))
    }

    // ── Notifications, admin (SET-A11, A12) ──

    @Test fun `SET-A11 a channel sends what its provider knows and keeps empty secrets`() {
        val ntfy = ChannelForm(provider = "ntfy", name = " Phone ", enabled = true, baseUrl = "https://ntfy.sh", ntfyTopic = "home", ntfyAuth = "token")
        assertEquals("""{"provider":"ntfy","name":"Phone","enabled":true,"config":{"baseUrl":"https://ntfy.sh","topic":"home","priority":"default","authType":"token"}}""", ntfy.body().toString())
        val mail = ChannelForm(provider = "email", emailTo = "a@b.c ", baseUrl = "ignored")
        assertEquals("""{"provider":"email","name":"","enabled":false,"config":{"toAddress":"a@b.c"}}""", mail.body().toString())
        val gotify = ChannelForm(gotifyToken = "tok", gotifyPriority = "8")
        assertTrue(gotify.body().toString().endsWith(""""config":{"baseUrl":"","priority":8},"secrets":{"appToken":"tok"}}"""))
        assertEquals("ntfy", ChannelForm.of(obj("""{"provider":"ntfy","config":{"topic":"t","priority":"high"}}""")).provider)
    }

    @Test fun `SET-A12 admin forms check ports, senders, scopes and coordinates`() {
        assertEquals(setOf("port", "fromAddress"), AdminRules.emailProblems("0", "nope", emptySet()).keys)
        assertTrue(AdminRules.emailProblems("", "nope", setOf("port", "fromAddress")).isEmpty())
        assertNull(AdminRules.scopes(true, mapOf("tasks" to "read")))
        assertEquals(listOf("budget:write", "tasks:read"), AdminRules.scopes(false, mapOf("tasks" to "read", "budget" to "write", "notes" to "none")))
        assertTrue(WeatherRules.validCoords("52.52", "13.40"))
        assertFalse(WeatherRules.validCoords("95", "13"))
        assertEquals("52.5200", WeatherRules.coord(52.52))
        assertEquals("Mitte, Berlin, Germany", WeatherRules.placeMeta(obj("""{"admin2":"Mitte","admin1":"Berlin","country":"Germany"}""")))
    }
}
