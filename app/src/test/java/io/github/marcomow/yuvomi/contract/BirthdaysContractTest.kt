package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.birthdays.Birthday
import io.github.marcomow.yuvomi.feature.birthdays.BirthdaysApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
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
 * docs/spec/birthdays.md against the real server (skipped without `YUVOMI_CONTRACT_URL`). Every
 * test makes its own fixtures with unique names and removes them again.
 */
class BirthdaysContractTest : ContractTestBase() {

    private fun alex(): Pair<YuvomiClient, BirthdaysApi> = signedIn("alex").first.let { it to BirthdaysApi(it) }

    private suspend fun BirthdaysApi.all(): List<Birthday> = BirthdaysApi.decodeList(list())

    private suspend fun expectHttp(status: Int, block: suspend () -> Unit): ApiProblem.Http {
        try {
            block()
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            return e
        }
        fail("expected HTTP $status")
        error("unreachable")
    }

    /** A contact fixture through the contacts API (not one of this module's operations). */
    private fun contact(c: YuvomiClient, name: String, birthday: String? = null): Int {
        val body = buildJsonObject {
            put("name", name)
            if (birthday != null) put("birthday", birthday)
        }
        val answer = raw(c, "POST", "/api/v1/contacts", body.toString()) as JsonObject
        return answer["data"]!!.jsonObject["id"]!!.jsonPrimitive.int
    }

    private fun dropContact(c: YuvomiClient, id: Int) {
        raw(c, "DELETE", "/api/v1/contacts/$id", "", tolerate = true)
    }

    /** BDAY-C1 (GET /birthdays). */
    @Test fun `BDAY-C1 the seeded birthdays come hydrated and by proximity`() = runBlocking<Unit> {
        val (_, api) = alex()
        val list = api.all()
        assertTrue(list.isNotEmpty())
        assertTrue(list.all { it.nextBirthday != null && it.daysUntil != null })
        assertEquals(list.sortedBy { it.daysUntil }.map { it.daysUntil }, list.map { it.daysUntil })
        // A member's birthday brings the member's identity along (BIRTHDAY_SELECT).
        list.filter { it.familyUserId != null }.forEach { assertNotNull(it.familyDisplayName) }
    }

    /** BDAY-C2 (POST, PUT, DELETE /birthdays). */
    @Test fun `BDAY-C2 a birthday without a year is created, edited in part and deleted`() = runBlocking<Unit> {
        val (_, api) = alex()
        val name = unique("Ada")
        val body = buildJsonObject {
            put("name", name)
            put("birth_date", "2000-02-29")
            put("year_unknown", true)
            put("name_day", "02-29")
            put("notes", "Tea #garden")
            put("photo_data", JsonNull)
            put("reminder_offset", "custom")
            put("reminder_custom_amount", "3")
            put("reminder_custom_unit", "days")
        }
        val key = YuvomiClient.newIdempotencyKey()
        val created = api.create(body, key)
        try {
            assertEquals(name, created.name)
            assertTrue(created.yearUnknown)
            assertEquals("2000-02-29", created.birthDate)
            assertNull(created.nextAge)
            assertEquals("02-29", created.nameDay)
            assertEquals("custom", created.reminderOffset)
            assertEquals("3", created.reminderCustomAmount)
            assertEquals(created.id, api.create(body, key).id)

            // Partial: only the name changes, the reminder stays.
            val renamed = api.update(created.id, buildJsonObject { put("name", "$name 2") })
            assertEquals("$name 2", renamed.name)
            assertEquals("custom", renamed.reminderOffset)
            assertEquals("Tea #garden", renamed.notes)
            // Turning the year on needs the full date.
            expectHttp(400) { api.update(created.id, buildJsonObject { put("year_unknown", false) }) }
            val dated = api.update(created.id, buildJsonObject { put("year_unknown", false); put("birth_date", "1990-03-01") })
            assertFalse(dated.yearUnknown)
            assertNotNull(dated.nextAge)
            expectHttp(400) { api.update(created.id, buildJsonObject { put("name_day", "02-30") }) }
            expectHttp(400) { api.update(created.id, buildJsonObject { put("reminder_custom_amount", "1000") }) }
        } finally {
            api.delete(created.id)
        }
        assertTrue(api.all().none { it.id == created.id })
        assertEquals("birthdays_birthday_not_found", expectHttp(404) { api.delete(created.id) }.reason)
        assertEquals("birthdays_birthday_not_found", expectHttp(404) { api.update(created.id, buildJsonObject { put("name", "x") }) }.reason)
    }

    /** BDAY-C3 (PUT /birthdays/{id} with `contact_id`). */
    @Test fun `BDAY-C3 a birthday links to one contact and refuses a taken one`() = runBlocking<Unit> {
        val (c, api) = alex()
        val name = unique("Grete")
        val contactId = contact(c, name)
        val first = api.create(buildJsonObject { put("name", name); put("birth_date", "1960-06-01") }, YuvomiClient.newIdempotencyKey())
        val second = api.create(buildJsonObject { put("name", unique("Other")); put("birth_date", "1961-06-01") }, YuvomiClient.newIdempotencyKey())
        try {
            // Same name, both unlinked: the quiet hint.
            assertEquals(contactId, api.all().first { it.id == first.id }.suggestedContactId)
            assertEquals(contactId, api.link(first.id, contactId).contactId)
            val listed = api.all().first { it.id == first.id }
            assertEquals(name, listed.contactName)
            assertNull(listed.suggestedContactId)
            assertEquals("birthdays_contact_linked", expectHttp(409) { api.link(second.id, contactId) }.reason)
            assertEquals("birthdays_selected_contact_not_found", expectHttp(400) { api.link(second.id, 999_999) }.reason)
            assertNull(api.link(first.id, null).contactId)
        } finally {
            api.delete(first.id)
            api.delete(second.id)
            dropContact(c, contactId)
        }
    }

    /** BDAY-C4 (GET /birthdays/import/candidates, POST /birthdays/import). */
    @Test fun `BDAY-C4 contacts with a birthday are imported once`() = runBlocking<Unit> {
        val (c, api) = alex()
        val name = unique("Imported")
        val contactId = contact(c, name, "1975-09-12")
        val without = contact(c, unique("NoDate"))
        try {
            val candidates = api.candidates()
            val candidate = candidates.withBirthday.first { it.id == contactId }
            assertEquals("1975-09-12", candidate.birthday)
            assertFalse(candidate.alreadyImported)
            assertTrue(candidates.withoutBirthday.any { it.id == without })

            val result = api.import(listOf(contactId, without), YuvomiClient.newIdempotencyKey())
            assertEquals(1, result.imported)
            assertEquals(1, result.skipped)
            assertTrue(api.candidates().withBirthday.first { it.id == contactId }.alreadyImported)
            assertEquals(0, api.import(listOf(contactId), YuvomiClient.newIdempotencyKey()).imported)
            val imported = api.all().first { it.contactId == contactId }
            assertEquals(name, imported.name)
            api.delete(imported.id)
        } finally {
            dropContact(c, contactId)
            dropContact(c, without)
        }
        expectHttp(400) { api.import(emptyList(), YuvomiClient.newIdempotencyKey()) }
    }

    /** BDAY-C5 (GET, DELETE /birthdays/feed, POST /birthdays/feed/regenerate). */
    @Test fun `BDAY-C5 the feed address is created, replaced and revoked`() = runBlocking<Unit> {
        val api = BirthdaysApi(signedIn("linda").first)
        val before = api.feed()
        try {
            val on = api.regenerateFeed()
            assertNotNull(on.token)
            assertTrue(on.url!!.endsWith("/feed/birthdays/${on.token}.ics"))
            assertEquals(on.token, api.feed()?.token)
            val next = api.regenerateFeed()
            assertFalse(next.token == on.token)
            api.disableFeed()
            assertNull(api.feed())
        } finally {
            if (before != null) api.regenerateFeed() else api.disableFeed()
        }
    }
}
