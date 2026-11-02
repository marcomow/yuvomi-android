package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.contacts.BirthField
import io.github.marcomow.yuvomi.feature.contacts.Contact
import io.github.marcomow.yuvomi.feature.contacts.ContactDraft
import io.github.marcomow.yuvomi.feature.contacts.ContactsApi
import io.github.marcomow.yuvomi.feature.contacts.ContactsRules
import io.github.marcomow.yuvomi.feature.contacts.VCard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * docs/spec/contacts.md against upstream's image at the pinned version, seeded by `seed-demo.js`.
 * Every test makes its own contacts, categories and birthdays with unique names and removes them.
 */
class ContactsContractTest : ContractTestBase() {
    private fun alex(): Pair<YuvomiClient, ContactsApi> = signedIn("alex").first.let { it to ContactsApi(it) }

    private fun expectHttp(status: Int, reason: String? = null, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected HTTP $status")
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            if (reason != null) assertEquals(reason, e.reason)
        }
    }

    private suspend fun ContactsApi.make(first: String, last: String = "Contract", category: String = "misc", birthday: BirthField = BirthField()): Contact =
        create(
            ContactsRules.body(
                ContactDraft(firstName = first, lastName = last, category = category, phones = listOf("work" to "+49 30 1"), emails = listOf("" to "a@example.org"), birth = birthday),
                original = null, nameTouched = false, emailsLocked = false, categoryKnown = true,
            ),
            YuvomiClient.newIdempotencyKey(),
        )

    /** CONT-A1: the seeded list, categories and meta decode as the screens read them. */
    @Test fun `CONT-A1 the seeded contacts, categories and tones decode`() = runBlocking {
        val (_, api) = alex()
        val categories = ContactsApi.decodeCategories(api.categories())
        assertTrue(categories.any { it.key == "doctor" && it.labelKey == "contacts.categoryDoctor" })
        assertTrue(categories.any { it.key == "family" && it.labelKey == null && !it.name.isNullOrEmpty() })
        val contacts = ContactsApi.decodeContacts(api.list())
        assertTrue(contacts.size >= 12)
        assertTrue(contacts.any { it.category == "doctor" && it.phone == "+49 231 445 2210" })
        val colors = ContactsApi.decodeColors(api.meta())
        assertEquals(7, colors.size)
        assertTrue(colors.all { it.startsWith("var(--") })
    }

    /** CONT-A2: create, read, edit and delete answer as the app expects; a deleted contact is a 404 with its reason. */
    @Test fun `CONT-A2 create, read, update and delete a contact`() = runBlocking {
        val (_, api) = alex()
        val first = unique("Anna")
        val created = api.make(first, birthday = BirthField(yearUnknown = true, month = 3, day = 15))
        try {
            assertEquals("$first Contract", created.name)
            assertEquals("Contract", created.lastName)
            assertEquals("2000-03-15", created.birthday)
            assertTrue(created.birthdayYearUnknown)
            assertEquals(listOf("work"), created.phones?.map { it.label })
            assertEquals(listOf("other"), created.emails?.map { it.label })
            assertTrue(ContactsApi.decodeContacts(api.list()).any { it.id == created.id })

            val full = api.get(created.id)
            assertEquals("+49 30 1", full.phones?.single()?.value)
            assertTrue(full.phones!!.single().isPrimary)

            val draft = ContactsRules.draft(full, "misc").copy(phones = listOf("" to "123", "Mobile" to "456"), notes = "#contract note")
            val updated = api.update(created.id, ContactsRules.body(draft, full, nameTouched = false, emailsLocked = false, categoryKnown = true))
            assertEquals("123", updated.phone)
            assertEquals(listOf("123", "456"), updated.phones?.map { it.value })
            assertEquals("#contract note", updated.notes)
            assertEquals("2000-03-15", updated.birthday)

            val vcard = String(api.vcard(created.id))
            assertTrue(vcard.startsWith("BEGIN:VCARD"))
            val parsed = VCard.parse(vcard)
            assertEquals(created.name, parsed.name)
            assertEquals("2000-03-15", parsed.birthday)
            assertTrue(parsed.birthdayYearUnknown)
        } finally {
            api.delete(created.id)
        }
        expectHttp(404, "contacts_contact_not_found") { api.get(created.id) }
        expectHttp(404, "contacts_contact_not_found") { api.delete(created.id) }
        expectHttp(404, "contacts_contact_not_found") { api.update(created.id, buildJsonObject { put("notes", "x") }) }
        expectHttp(404) { api.vcard(created.id) }
    }

    /** CONT-A3: the server names the case of a refused form (`fieldCase`). */
    @Test fun `CONT-A3 refusals carry their reasons`() {
        val (_, api) = alex()
        expectHttp(400, "contacts_selected_category_not_found") { api.make(unique("Gone"), category = "no_such_category_" + System.nanoTime()) }
        expectHttp(400, "contacts_name_too_long") { api.make("x".repeat(250)) }
        expectHttp(400, "contacts_email_invalid") {
            api.create(buildJsonObject {
                put("name", unique("Mail"))
                put("emails", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("label", "other"); put("value", "no-at-sign") })))
            }, YuvomiClient.newIdempotencyKey())
        }
    }

    /** CONT-A4: categories are added, renamed, recoloured, reordered and deleted; in-use and duplicates are refused. */
    @Test fun `CONT-A4 the category manager's calls`() = runBlocking {
        val (_, api) = alex()
        val before = ContactsApi.decodeCategories(api.categories())
        val name = unique("Cat")
        val cat = api.createCategory(name, YuvomiClient.newIdempotencyKey())
        try {
            assertEquals(name, cat.name)
            expectHttp(409, "category_exists") { api.createCategory(name.uppercase(), YuvomiClient.newIdempotencyKey()) }
            val renamed = api.renameCategory(cat.key, "$name 2")
            assertEquals(cat.key, renamed.key)
            assertEquals("$name 2", renamed.name)
            val colored = api.recolorCategory(cat.key, "var(--color-danger)")
            assertEquals("var(--color-danger)", colored.color)
            assertEquals("$name 2", colored.name)
            assertNull(api.recolorCategory(cat.key, null).color)
            // A seeded category keeps its translation when only its colour changes.
            val doctor = before.first { it.key == "doctor" }
            val recolored = api.recolorCategory("doctor", doctor.color ?: "var(--color-success)")
            assertEquals("contacts.categoryDoctor", recolored.labelKey)
            if (doctor.color == null) api.recolorCategory("doctor", null)

            val order = listOf(cat.key) + before.map { it.key }
            assertEquals(order, api.reorderCategories(order).map { it.key })
            api.reorderCategories(before.map { it.key } + cat.key)

            val used = api.make(unique("Used"), category = cat.key)
            try {
                expectHttp(409, "category_in_use") { api.deleteCategory(cat.key) }
            } finally {
                api.delete(used.id)
            }
        } finally {
            api.deleteCategory(cat.key)
        }
        expectHttp(404) { api.renameCategory(cat.key, "x") }
        assertFalse(ContactsApi.decodeCategories(api.categories()).any { it.key == cat.key })
    }

    /** CONT-A5: "Add to birthdays" links a new birthday; "Link" ties an existing one (`birthday_id`, `suggested_birthday_id`). */
    @Test fun `CONT-A5 the detail's two ways into Birthdays`() = runBlocking {
        val (client, api) = alex()
        val dated = api.make(unique("Dated"), birthday = BirthField(picker = "1980-07-04"))
        var birthdayIds = emptyList<Int>()
        try {
            api.importBirthday(dated.id, YuvomiClient.newIdempotencyKey())
            val imported = api.get(dated.id)
            assertNotNull(imported.birthdayId)
            birthdayIds = birthdayIds + imported.birthdayId!!

            // A birthday with the same name as a contact without a birthday is suggested, then linked.
            val first = unique("Linked")
            val plain = api.make(first)
            try {
                val made = raw(client, "POST", "/api/v1/birthdays", """{"name":"${plain.name}","birth_date":"1975-01-02"}""") as JsonObject
                val birthdayId = made["data"]!!.jsonObject["id"]!!.jsonPrimitive.int
                birthdayIds = birthdayIds + birthdayId
                val suggested = api.get(plain.id)
                assertEquals(birthdayId, suggested.suggestedBirthdayId)
                api.linkBirthday(birthdayId, plain.id)
                val linked = api.get(plain.id)
                assertEquals(birthdayId, linked.birthdayId)
                assertEquals("1975-01-02", linked.birthdayLinkDate)
                assertNull(linked.suggestedBirthdayId)
            } finally {
                api.delete(plain.id)
            }
        } finally {
            birthdayIds.forEach { raw(client, "DELETE", "/api/v1/birthdays/$it", "", tolerate = true) }
            api.delete(dated.id)
        }
        expectHttp(404, "birthdays_birthday_not_found") { api.linkBirthday(Int.MAX_VALUE, dated.id) }
    }
}
