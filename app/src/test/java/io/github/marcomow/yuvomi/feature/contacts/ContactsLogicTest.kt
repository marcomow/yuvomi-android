package io.github.marcomow.yuvomi.feature.contacts

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.text.Collator
import java.util.Locale

/** docs/spec/contacts.md, CONT-A2 to A8: the pure ports of `contacts.js`, `contact-name.js` and `vcard.js`. */
class ContactsLogicTest {
    private val english = Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n/en.json").readText()) +
        Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n-android/contacts/en.json").readText())
    private val strings = Strings("en", english, english) { if (it == 1.0) "one" else "other" }
    private val collator = Collator.getInstance(Locale.ENGLISH)

    private val categories = listOf(
        ContactCategory("doctor", labelKey = "contacts.catDoctor", sortOrder = 0, color = "var(--color-success)"),
        ContactCategory("family", name = "Family", sortOrder = 1),
        ContactCategory("misc", labelKey = "contacts.catMisc", sortOrder = 2),
    )

    private fun c(id: Int, name: String, category: String = "misc", last: String? = null, phone: String? = null, email: String? = null, member: Int? = null) =
        Contact(id = id, name = name, category = category, lastName = last, phone = phone, email = email, familyUserId = member)

    @Test fun `names compose, split and sort like contact-name js`() {
        assertEquals("Anna Maria Miller", ContactsRules.composeDisplayName(" Anna ", "Miller", "Maria"))
        assertNull(ContactsRules.composeDisplayName(" ", null))
        assertEquals("Anna Maria" to "Miller", ContactsRules.splitDisplayName("Anna  Maria Miller"))
        assertEquals("Cher" to null, ContactsRules.splitDisplayName("Cher"))
        assertEquals(null to null, ContactsRules.splitDisplayName("  "))
        assertEquals("Weber", ContactsRules.sortKey(c(1, "Dr. Hans Weber", last = "Weber")))
        assertEquals("Dr. Hans Weber", ContactsRules.sortKey(c(1, "Dr. Hans Weber", last = " ")))
    }

    @Test fun `the list orders by category position then by last name`() {
        val list = listOf(
            c(1, "Zoe Adams", "misc", last = "Adams"),
            c(2, "Bob", "family"),
            c(3, "Dr. Weber", "doctor"),
            c(4, "Anna Zimmer", "misc", last = "Zimmer"),
            c(5, "Ghost", "gone"),
        )
        assertEquals(listOf(3, 2, 1, 4, 5), ContactsRules.sorted(list, categories, collator).map { it.id })
        val groups = ContactsRules.groups(ContactsRules.sorted(list, categories, collator), categories)
        assertEquals(listOf("doctor", "family", "misc", "gone"), groups.map { it.first })
    }

    @Test fun `search matches name phone and email, and the category filter narrows`() {
        val list = listOf(c(1, "Anna", phone = "+49 30 1234"), c(2, "Ben", email = "ben@example.org", category = "doctor"), c(3, "Clara"))
        assertEquals(listOf(1), ContactsRules.filter(list, null, "30 12").map { it.id })
        assertEquals(listOf(2), ContactsRules.filter(list, null, "EXAMPLE").map { it.id })
        assertEquals(listOf(3), ContactsRules.filter(list, null, "clar").map { it.id })
        assertEquals(listOf(2), ContactsRules.filter(list, "doctor", "").map { it.id })
        assertTrue(ContactsRules.filter(list, "doctor", "anna").isEmpty())
    }

    @Test fun `chips only for used categories, none with a single one, the active one kept`() {
        val one = listOf(c(1, "A"), c(2, "B"))
        assertTrue(ContactsRules.filterCategoryKeys(one, categories, null).isEmpty())
        assertEquals(listOf("family", "misc"), ContactsRules.filterCategoryKeys(one, categories, "family"))
        val two = one + c(3, "C", "doctor")
        assertEquals(listOf("doctor", "misc"), ContactsRules.filterCategoryKeys(two, categories, null))
    }

    @Test fun `labels come from label_key, name or the key itself`() {
        assertEquals("Doctor", ContactsRules.categoryLabel("doctor", categories, strings))
        assertEquals("Family", ContactsRules.categoryLabel("family", categories, strings))
        assertEquals("orphan", ContactsRules.categoryLabel("orphan", categories, strings))
        assertEquals("family", ContactsRules.defaultCategory("family", categories))
        assertEquals("misc", ContactsRules.defaultCategory("gone", categories))
        assertEquals("doctor", ContactsRules.defaultCategory(null, categories.take(1)))
    }

    @Test fun `a member's emails are locked for everyone but the member and an admin`() {
        val member = c(1, "Alex", member = 7)
        assertTrue(ContactsRules.emailsLocked(member, userId = 3, isAdmin = false))
        assertFalse(ContactsRules.emailsLocked(member, userId = 7, isAdmin = false))
        assertFalse(ContactsRules.emailsLocked(member, userId = 3, isAdmin = true))
        assertFalse(ContactsRules.emailsLocked(c(2, "Plain"), userId = 3, isAdmin = false))
    }

    @Test fun `the detail falls back to the single fields and formats addresses`() {
        val plain = Contact(id = 1, name = "A", phone = "123", email = "a@b.c", address = "Main St 1")
        assertEquals(listOf("123"), ContactsRules.phones(plain).map { it.value })
        assertEquals(listOf("a@b.c"), ContactsRules.emails(plain).map { it.value })
        assertEquals(listOf("Main St 1" to null), ContactsRules.addresses(plain))
        val rich = plain.copy(
            phones = listOf(ContactValue(label = "work", value = "9"), ContactValue(label = "other", value = "8")),
            addresses = listOf(ContactAddress(label = "home", street = "Elm 2", postalCode = "10115", city = "Berlin", country = "DE")),
        )
        assertEquals(listOf("work", null), ContactsRules.phones(rich).map { ContactsRules.subLabel(it.label) })
        assertEquals(listOf("Elm 2, 10115 Berlin, DE" to "home"), ContactsRules.addresses(rich))
        assertEquals("9", ContactsRules.firstPhone(rich))
        assertEquals("Elm 2, 10115 Berlin, DE", ContactsRules.firstAddress(rich))
        assertEquals("https://www.openstreetmap.org/search?query=Elm%202%2C%20Berlin", ContactsRules.mapsUrl("Elm 2, Berlin"))
        assertEquals("https://example.org", ContactsRules.externalHref("example.org"))
        assertEquals("+4930123", ContactsRules.dialable("+49 (30) 123"))
    }

    @Test fun `the birthday row shows, adds or links as birthdayRowAction decides`() {
        val linked = c(1, "A").copy(birthdayId = 4, birthdayLinkDate = "2000-03-15", birthdayLinkYearUnknown = true)
        assertEquals("2000-03-15" to true, ContactsRules.birthdayShown(linked))
        assertEquals(ContactsRules.BirthdayAction.SHOW, ContactsRules.birthdayAction(linked, mayOpenBirthdays = true, mayImport = true))
        assertNull(ContactsRules.birthdayAction(linked, mayOpenBirthdays = false, mayImport = true))
        val dated = c(2, "B").copy(birthday = "1990-01-02")
        assertEquals(ContactsRules.BirthdayAction.ADD, ContactsRules.birthdayAction(dated, true, true))
        assertNull(ContactsRules.birthdayAction(dated, true, false))
        val suggested = dated.copy(suggestedBirthdayId = 9)
        assertNull(ContactsRules.birthdayAction(suggested, true, true))
        assertTrue(ContactsRules.showsSuggestion(suggested, mayLink = true))
        assertFalse(ContactsRules.showsSuggestion(suggested, mayLink = false))
    }

    @Test fun `the form sends the web app's payload`() {
        val draft = ContactDraft(
            firstName = " Anna ", lastName = "Miller", category = "doctor",
            phones = listOf("" to " 123 ", "work" to "456", "x" to " "),
            emails = listOf("" to "a@b.c"),
            address = " ", notes = "Note",
            birth = BirthField(yearUnknown = true, month = 3, day = 15),
        )
        val body = ContactsRules.body(draft, original = null, nameTouched = false, emailsLocked = false, categoryKnown = true)
        assertEquals("Anna Miller", body["name"]!!.jsonPrimitive.content)
        assertEquals("Anna", body["firstName"]!!.jsonPrimitive.content)
        assertEquals("123", body["phone"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["address"])
        assertEquals("2000-03-15", body["birthday"]!!.jsonPrimitive.content)
        assertEquals("true", body["birthday_year_unknown"]!!.jsonPrimitive.content)
        val phones = body["phones"] as JsonArray
        assertEquals(2, phones.size)
        assertEquals("other", phones[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("true", phones[0].jsonObject["isPrimary"]!!.jsonPrimitive.content)
        assertEquals("work", phones[1].jsonObject["label"]!!.jsonPrimitive.content)

        // An unstructured name stays as it is unless the name was edited; locked e-mails and an orphaned category are left out.
        val original = Contact(id = 3, name = "Dr. Hans Weber", category = "gone", familyUserId = 2)
        val prefill = ContactsRules.draft(original, "misc")
        assertEquals("Dr. Hans", prefill.firstName)
        assertEquals("Weber", prefill.lastName)
        val edit = ContactsRules.body(prefill, original, nameTouched = false, emailsLocked = true, categoryKnown = false)
        assertEquals("Dr. Hans Weber", edit["name"]!!.jsonPrimitive.content)
        assertFalse("firstName" in edit)
        assertFalse("email" in edit || "emails" in edit)
        assertFalse("category" in edit)
        assertEquals(JsonNull, edit["birthday"])
    }

    @Test fun `the birthday field keeps month and day across the year switch`() {
        val f = BirthField.of("1985-02-28", yearUnknown = false)
        val yearless = f.toggle(true)
        assertEquals(2, yearless.month)
        assertEquals(28, yearless.day)
        assertEquals("2000-02-28", yearless.read().date)
        assertEquals("1985-02-28", yearless.toggle(false).picker)
        assertNull(BirthField(yearUnknown = true, month = 4).read().date)
        assertFalse(BirthField(yearUnknown = true, month = 4).read().complete)
        assertTrue(BirthField(yearUnknown = true).read().complete)
        assertNull(BirthField(yearUnknown = true, month = 1, day = 31).withMonth(4).day)
        assertEquals(29, BirthField.daysInMonth(2))
        assertEquals(BirthField(yearUnknown = true, month = 3, day = 15), BirthField.of("2000-03-15", yearUnknown = true))
    }

    @Test fun `vCards parse like vcard js`() {
        val text = """
            BEGIN:VCARD
            VERSION:3.0
            N:Müller;Anna;Maria;Dr.;
            FN:Dr. Anna Maria Müller
            TEL;TYPE=CELL:+49 30 123
            TEL;TYPE=WORK:+49 30 999
            EMAIL:anna@example.org
            ADR;TYPE=HOME:;;Elm 2;Berlin;;10115;DE
            NOTE:Line one\nline two\, with comma
            BDAY;X-APPLE-OMIT-YEAR=1604:1604-03-15
            CATEGORIES:Doctor,VIP
            END:VCARD
            BEGIN:VCARD
            VERSION:2.1
            FN;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:J=C3=BCrgen =
            Becker
            BDAY:19800704
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            TEL:123
            END:VCARD
        """.trimIndent()
        val cards = VCard.parseAll(text)
        assertEquals(3, cards.size)
        val anna = cards[0]
        assertEquals("Anna Maria Müller", anna.name)
        assertEquals("Müller", anna.lastName)
        assertEquals("Dr.", anna.namePrefix)
        assertEquals("+49 30 123", anna.phone)
        assertEquals("Elm 2, Berlin, 10115, DE", anna.address)
        assertEquals("Line one\nline two, with comma", anna.notes)
        assertEquals("2000-03-15", anna.birthday)
        assertTrue(anna.birthdayYearUnknown)
        assertEquals("doctor", ContactsRules.resolveCategory(anna.categories, categories, strings))
        assertEquals("Jürgen Becker", cards[1].name)
        assertEquals("1980-07-04", cards[1].birthday)
        assertNull(cards[2].name)
        assertEquals(VCard.Birthday("2000-02-29", true), VCard.parseBirthday("--0229"))
        assertNull(VCard.parseBirthday("--0230"))
        assertEquals(VCard.Birthday("1999-01-01", false), VCard.parseBirthday("1999"))
        assertEquals(VCard.Birthday("1990-05-06", false), VCard.parseBirthday("1990-05-06T00:00:00Z"))
    }

    @Test fun `an import marks names that exist in either order`() {
        val existing = listOf(Contact(id = 1, name = "Weber, Hans"), Contact(id = 2, name = "Anna Miller", firstName = "Anna", lastName = "Miller"))
        assertTrue(ContactsRules.existsByName(VCard.Parsed(name = "Hans Weber"), existing))
        assertTrue(ContactsRules.existsByName(VCard.Parsed(name = "Miller Anna", firstName = "Miller", lastName = "Anna"), existing))
        assertFalse(ContactsRules.existsByName(VCard.Parsed(name = "Hans Webers"), existing))
        val body = ContactsRules.importBody(VCard.Parsed(name = "X", birthday = "2000-01-02", birthdayYearUnknown = true), "misc")
        assertEquals(JsonPrimitive(true), body["birthday_year_unknown"])
        assertEquals(JsonPrimitive("misc"), body["category"])
    }

    @Test fun `errors say upstream's sentences with the server's limits`() {
        fun http(status: Int, reason: String?) = ApiProblem.Http(status, reason, "x")
        assertEquals("The name can be at most 200 characters long.", ContactsRules.errorSentence(http(400, "contacts_name_too_long"), strings))
        assertEquals(strings.t("contacts.errorContactGone"), ContactsRules.errorSentence(http(404, "contacts_contact_not_found"), strings))
        assertEquals(strings.t("contacts.emailLockedHint"), ContactsRules.errorSentence(http(403, "contact_email_protected"), strings))
        assertEquals(strings.t("common.errorNoPermission"), ContactsRules.errorSentence(http(403, "family_member_contact"), strings))
        assertEquals(strings.t("common.emailInUse"), ContactsRules.errorSentence(http(409, "email_in_use"), strings))
        assertEquals(strings.t("common.errorOfflineMutation"), ContactsRules.errorSentence(ApiProblem.Network(IOException("x")), strings))
        assertEquals(strings.t("common.errorGeneric"), ContactsRules.errorSentence(http(400, null), strings))
        assertTrue(ContactsRules.isGone(http(404, "contacts_contact_not_found")))
        assertFalse(ContactsRules.isGone(http(403, "contacts_contact_not_found")))
        assertEquals(strings.t("category.errorInUse", "count" to 3), ContactsRules.categoryError(ApiProblem.Http(409, "category_in_use", "Category is in use by 3 contacts."), strings))
        assertEquals(strings.t("category.errorLast"), ContactsRules.categoryError(http(409, "category_last"), strings))
    }

    @Test fun `the tag source lists contacts with hashtags in name or notes`() {
        val items = ContactsTagSource.items(
            listOf(
                Contact(id = 1, name = "Plumber #urgent", phone = "123"),
                Contact(id = 2, name = "Anna", notes = "met at #school and #School"),
                Contact(id = 3, name = "Ben", notes = "issue #42"),
            ),
        )
        assertEquals(listOf("contact:1", "contact:2"), items.map { it.key })
        assertEquals(setOf("urgent"), items[0].tags)
        assertEquals("123", items[0].subtitle)
        assertEquals(setOf("school"), items[1].tags)
        assertEquals("contacts/2", items[1].route)
    }

    @Test fun `the list answer decodes with members and birthday links`() {
        val json = ApiJson.parseToJsonElement(
            """{"data":[{"id":1,"name":"Alex","category":"family","family_user_id":3,"family_avatar_color":"#f00",
               "birthday_id":5,"birthday_link_date":"2000-05-01","birthday_link_year_unknown":1,"birthday_year_unknown":0,
               "phones":[{"id":1,"label":"other","value":"1","isPrimary":true}]}]}""",
        )
        val c = ContactsApi.decodeContacts(json).single()
        assertTrue(c.isMember)
        assertTrue(c.birthdayLinkYearUnknown)
        assertFalse(c.birthdayYearUnknown)
        assertEquals("1", c.phones!!.single().value)
        val meta = ApiJson.parseToJsonElement("""{"data":{"categories":[],"categoryColors":["var(--color-success)",""]}}""")
        assertEquals(listOf("var(--color-success)", ""), ContactsApi.decodeColors(meta))
        assertTrue(ApiJson.parseToJsonElement(ContactsApi.encodeContacts(listOf(c))) is JsonObject)
    }
}
