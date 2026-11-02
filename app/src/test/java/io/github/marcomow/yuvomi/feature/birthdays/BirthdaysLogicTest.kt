package io.github.marcomow.yuvomi.feature.birthdays

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Ports of the birthdays page's rules (docs/spec/birthdays.md), checked against what the web app computes. */
class BirthdaysLogicTest {
    private val english = Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n/en.json").readText()) +
        Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n-android/birthdays/en.json").readText())
    private val strings = Strings("en", english, english) { if (it == 1.0) "one" else "other" }

    /** A readable stand-in for ICU: "in 16 day", "2 week". */
    private val format = object : BirthdaysFormat {
        override fun relative(value: Int, unit: String) = if (value < 0) "${-value} $unit ago" else "in $value $unit"
        override fun duration(amount: Int, unit: String) = "$amount $unit"
        override fun fullDate(d: LocalDate) = d.toString()
        override fun monthDay(month: Int, day: Int) = "%02d/%02d".format(month, day)
        override fun dateText(d: LocalDate, weekday: Boolean) = (if (weekday) "${d.dayOfWeek.name.take(3)} " else "") + d.toString()
        override fun monthYear(ym: YearMonth) = ym.toString()
        override fun monthName(month: Int) = "M$month"
    }

    private fun b(id: Int, name: String, days: Int?, next: String? = null, notes: String? = null) =
        Birthday(id, name, daysUntil = days, nextBirthday = next, notes = notes)

    /** BDAY-A1: `sortByProximity`, `filteredBirthdays`, `birthdayListHtml`. */
    @Test fun `the list runs by the next birthday, searches name and notes, and groups only when months repeat`() {
        val list = listOf(b(1, "Zoe", 3), b(2, "Anna", 3), b(3, "Mike", 0), b(4, "Oma", 200, notes = "loves #garden roses"))
        assertEquals(listOf(3, 2, 1, 4), BirthdaysRules.sortByProximity(list).map { it.id })
        assertEquals(listOf(4), BirthdaysRules.filtered(list, "ROSES").map { it.id })
        assertFalse(BirthdaysRules.grouped(listOf(b(1, "a", 1, "2026-11-03"), b(2, "b", 40, "2026-12-12"))))
        assertTrue(BirthdaysRules.grouped(listOf(b(1, "a", 1, "2026-11-03"), b(2, "b", 4, "2026-11-06"))))
    }

    /** BDAY-A2: `daysFromTodayText`. */
    @Test fun `the countdown says today, tomorrow, then days, weeks, months and years`() {
        assertEquals("Today", BirthdaysRules.countdown(0, strings, format))
        assertEquals("Tomorrow", BirthdaysRules.countdown(1, strings, format))
        assertEquals("in 16 day", BirthdaysRules.countdown(16, strings, format))
        assertEquals("in 6 week", BirthdaysRules.countdown(45, strings, format))
        assertEquals("in 4 month", BirthdaysRules.countdown(120, strings, format))
        assertEquals("in 1 year", BirthdaysRules.countdown(365, strings, format))
        assertEquals(BirthdaysRules.Chip.SOON, BirthdaysRules.chip(7))
        assertEquals(BirthdaysRules.Chip.DEFAULT, BirthdaysRules.chip(8))
    }

    @Test fun `the age line names the weekday only within two weeks and no age without a year`() {
        val near = Birthday(1, "Mike", nextBirthday = "2026-11-09", daysUntil = 7, nextAge = 41)
        assertEquals("MON 2026-11-09 · turns 41", BirthdaysRules.ageMeta(near, strings, format))
        assertEquals("2026-12-30", BirthdaysRules.ageMeta(near.copy(daysUntil = 58, nextBirthday = "2026-12-30", nextAge = null), strings, format))
        assertEquals("03/04", BirthdaysRules.birthDateText("2000-03-04", true, format))
        assertEquals("1985-03-04", BirthdaysRules.birthDateText("1985-03-04", false, format))
        assertEquals("05/12", BirthdaysRules.nameDayText("05-12", format))
    }

    /** BDAY-A3: `storedReminderOffset`, `birthdayReminderOptions`, `reminderToSave`. */
    @Test fun `the reminder follows the server and keeps values no preset knows`() {
        assertEquals("0", BirthdaysRules.storedOffset(Birthday(1, "x", reminderOffset = null)))
        assertEquals("1440", BirthdaysRules.storedOffset(Birthday(1, "x", reminderOffset = "01440")))
        assertEquals("20160", BirthdaysRules.storedOffset(Birthday(1, "x", reminderOffset = "20160")))
        val options = BirthdaysRules.reminderOptions(Birthday(1, "x", reminderOffset = "20160"), strings, format)
        assertEquals(listOf("", "0", "1440", "2880", "10080", "20160", "custom"), options.map { it.value })
        assertEquals("2 week before", options.first { it.value == "20160" }.label)
        assertEquals("90 minute after midday", BirthdaysRules.unlistedLabel("-90", strings, format))
        assertEquals("3 day", BirthdaysRules.reminderText(Birthday(1, "x", reminderOffset = "custom", reminderCustomAmount = "3", reminderCustomUnit = "days"), strings, format))
        assertNull(BirthdaysRules.reminderToSave(BirthdaysRules.Reminder("custom", "0", "days")))
        assertEquals(mapOf("reminder_offset" to "1440"), BirthdaysRules.reminderToSave(BirthdaysRules.Reminder("1440", "abc", "days")))
        assertEquals(3, BirthdaysRules.reminderToSave(BirthdaysRules.Reminder("custom", "2", "weeks"))!!.size)
        assertTrue(BirthdaysRules.reminderIsSpecial(Birthday(1, "x", reminderOffset = null)))
        assertFalse(BirthdaysRules.reminderIsSpecial(Birthday(1, "x", reminderOffset = "1440")))
    }

    @Test fun `a name day needs month and day, and the 29th of February counts`() {
        assertEquals(BirthdaysRules.NameDay("02-29", true), BirthdaysRules.normalizeNameDay(2, 29))
        assertEquals(BirthdaysRules.NameDay(null, true), BirthdaysRules.normalizeNameDay(null, null))
        assertFalse(BirthdaysRules.normalizeNameDay(4, 31).complete)
        assertFalse(BirthdaysRules.normalizeNameDay(4, null).complete)
    }

    @Test fun `the avatar is the entry's picture, then the member's, then neutral`() {
        val member = Birthday(1, "Emma", familyUserId = 3, familyDisplayName = "Emma Johnson", familyAvatarColor = "#DB2777")
        assertEquals(BirthdaysRules.Avatar("Emma Johnson", "#DB2777", null), BirthdaysRules.avatar(member))
        assertEquals("data:x", BirthdaysRules.avatar(member.copy(photoData = "data:x")).picture)
        assertEquals(BirthdaysRules.NEUTRAL, BirthdaysRules.avatar(Birthday(2, "Oma")).color)
    }

    @Test fun `the link field offers free contacts and the linked one, by name`() {
        val c = ImportCandidates(
            withBirthday = listOf(ImportCandidate(1, "zed", alreadyImported = true), ImportCandidate(2, "Bea", alreadyImported = true)),
            withoutBirthday = listOf(ImportCandidate(3, "anna")),
        )
        assertEquals(listOf(3, 2), BirthdaysRules.linkable(c, current = 2).map { it.id })
    }

    /** BDAY-A4: `errorSentence` for the birthday reasons. */
    @Test fun `errors use the app's sentences`() {
        assertEquals("birthdays.errorBirthdayGone", BirthdaysRules.errorKey(ApiProblem.Http(404, "birthdays_birthday_not_found", "x")))
        assertEquals("birthdays.errorContactLinked", BirthdaysRules.errorKey(ApiProblem.Http(409, "birthdays_contact_linked", "x")))
        assertEquals("common.errorNoPermission", BirthdaysRules.errorKey(ApiProblem.Http(403, "cross_module_access", "x")))
        assertTrue(BirthdaysRules.isGone(ApiProblem.Http(404, "birthdays_birthday_not_found", null)))
        assertFalse(BirthdaysRules.isGone(ApiProblem.Http(403, "birthdays_birthday_not_found", null)))
    }

    @Test fun `the list answer decodes, numbers as text where the column is text`() {
        val list = BirthdaysApi.decodeList(
            ApiJson.parseToJsonElement(
                """{"data":[{"id":5,"name":"Mike","birth_date":"1985-11-02","year_unknown":0,"reminder_offset":"1440",
                   "reminder_custom_amount":3,"reminder_custom_unit":"days","days_until":0,"next_age":41,"suggested_contact_id":9}]}""",
            ),
        )
        assertEquals("3", list.single().reminderCustomAmount)
        assertEquals(9, list.single().suggestedContactId)
        assertFalse(list.single().yearUnknown)
    }

    /** BDAY-A12: the Tags view's source. */
    @Test fun `birthdays with hashtags in name or notes become tagged items that open their sheet`() {
        val items = BirthdaysTagSource.items(listOf(b(1, "Oma", 3, notes = "#family dinner"), b(2, "Mike", 5)))
        assertEquals(listOf("birthday:1"), items.map { it.key })
        assertEquals(setOf("family"), items.single().tags)
        assertEquals(BirthdaysRoutes.birthday(1), items.single().route)
    }
}
