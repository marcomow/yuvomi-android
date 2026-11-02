package io.github.marcomow.yuvomi.feature.birthdays

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The words and numbers the page derives (relative days, durations, dates) need the platform's
 * formatters; they come in through this interface so the rules stay testable on the JVM.
 */
interface BirthdaysFormat {
    /** `relativeUnit`: "in 16 days", "3 weeks ago"; [unit] is `day`, `week`, `month` or `year`. */
    fun relative(value: Int, unit: String): String

    /** `formatUnit(..., { unitDisplay: 'long' })`: "2 weeks"; [unit] is `minute`, `hour`, `day` or `week`. */
    fun duration(amount: Int, unit: String): String

    /** A full date with its year ("Mar 4, 1985"). */
    fun fullDate(d: LocalDate): String

    /** Day and month without a year ("March 4"). */
    fun monthDay(month: Int, day: Int): String

    /** `dateText`: without the year in the current year, with a short weekday when asked. */
    fun dateText(d: LocalDate, weekday: Boolean): String

    /** A month and its year ("November 2026"). */
    fun monthYear(ym: YearMonth): String

    /** The month's name ("March"). */
    fun monthName(month: Int): String
}

/** The web page's rules, as pure functions (docs/spec/birthdays.md). */
object BirthdaysRules {

    // ── List ──

    /** `sortByProximity`: the next one first, then by name. */
    fun sortByProximity(list: List<Birthday>): List<Birthday> =
        list.sortedWith(compareBy<Birthday> { it.daysUntil ?: 9999 }.thenBy { it.name })

    /** `filteredBirthdays`: the search reads name and notes. */
    fun filtered(list: List<Birthday>, query: String): List<Birthday> {
        val q = query.trim().lowercase()
        val hits = if (q.isEmpty()) list else list.filter { it.name.lowercase().contains(q) || it.notes.orEmpty().lowercase().contains(q) }
        return sortByProximity(hits)
    }

    /** `birthdayListHtml`: month heads only when there are on average two birthdays a month. */
    fun grouped(list: List<Birthday>): Boolean {
        val months = list.mapNotNull { it.nextBirthday?.take(7)?.takeIf(String::isNotEmpty) }.toSet()
        return months.isNotEmpty() && list.size >= 2 * months.size
    }

    /** `daysFromTodayText`: Today, Tomorrow, then days, weeks, months, years. */
    fun countdown(days: Int, strings: Strings, format: BirthdaysFormat): String {
        if (days == 0) return strings.t("common.today")
        if (days == 1) return strings.t("common.tomorrow")
        if (days == -1) return strings.t("common.yesterday")
        val sign = if (days < 0) -1 else 1
        val a = abs(days)
        return when {
            a <= 30 -> format.relative(days, "day")
            a <= 60 -> format.relative(sign * (a / 7.0).roundToInt(), "week")
            a <= 364 -> format.relative(sign * (a / 30.44).roundToInt(), "month")
            else -> format.relative(sign * maxOf(1, (a / 365.25).roundToInt()), "year")
        }
    }

    /** The countdown chip's tone: today, within a week, or later. */
    enum class Chip { TODAY, SOON, DEFAULT }

    fun chip(days: Int?): Chip = when {
        days == 0 -> Chip.TODAY
        days != null && days <= 7 -> Chip.SOON
        else -> Chip.DEFAULT
    }

    /** `ageMeta`: "Mon, Nov 9 · turns 41"; the weekday only within two weeks. */
    fun ageMeta(b: Birthday, strings: Strings, format: BirthdaysFormat): String {
        val next = b.nextBirthday?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return ""
        val near = b.daysUntil != null && b.daysUntil < 14
        val date = format.dateText(next, weekday = near)
        return if (b.nextAge != null) "$date · ${strings.t("birthdays.turnsAge", "age" to b.nextAge)}" else date
    }

    /** `birthDateText`: the full date, or day and month without a year. */
    fun birthDateText(date: String?, yearUnknown: Boolean, format: BirthdaysFormat): String {
        val d = date?.takeIf { it.length >= 10 } ?: return ""
        if (!yearUnknown) return runCatching { format.fullDate(LocalDate.parse(d.take(10))) }.getOrDefault(d)
        val month = d.substring(5, 7).toIntOrNull() ?: return d
        val day = d.substring(8, 10).toIntOrNull() ?: return d
        return format.monthDay(month, day)
    }

    /** `nameDayReadText`: "May 12". */
    fun nameDayText(nameDay: String?, format: BirthdaysFormat): String {
        val parts = nameDay?.split('-')?.mapNotNull { it.toIntOrNull() } ?: return ""
        if (parts.size != 2 || parts[0] == 0 || parts[1] == 0) return ""
        return format.monthDay(parts[0], parts[1])
    }

    // ── Reminder (REMINDER_OFFSETS, storedReminderOffset, birthdayReminderOptions) ──

    data class Option(val value: String, val label: String)

    private val PRESETS = listOf(
        "" to "reminders.offsetNone",
        "0" to "birthdays.reminderOnDay",
        "1440" to "reminders.offset1day",
        "2880" to "reminders.offset2days",
        "10080" to "reminders.offset1week",
        "custom" to "reminders.offsetCustom",
    )
    private val PRESET_VALUES = PRESETS.map { it.first }.toSet()

    /** What a new birthday starts with: one day before. */
    const val NEW_REMINDER = "1440"
    const val REMINDER_AMOUNT_MAX = 999
    val REMINDER_UNITS = listOf("minutes", "hours", "days", "weeks")

    /** `storedReminderMinutes`: `parseInt`, 0 when it is none. */
    private fun minutes(value: String): Int = Regex("^\\s*-?\\d+").find(value)?.value?.trim()?.toIntOrNull() ?: 0

    /** `storedReminderOffset`: no stored value is "on the day", as the server reads it. */
    fun storedOffset(b: Birthday): String {
        val stored = b.reminderOffset ?: return "0"
        if (stored in PRESET_VALUES) return stored
        val preset = minutes(stored).toString()
        return if (preset in PRESET_VALUES) preset else stored
    }

    /** `birthdayReminderOptions`: the presets, and a stored value no preset knows as its own option. */
    fun reminderOptions(b: Birthday?, strings: Strings, format: BirthdaysFormat): List<Option> {
        val options = PRESETS.map { (value, key) -> Option(value, strings.t(key)) }.toMutableList()
        val shown = b?.let(::storedOffset) ?: NEW_REMINDER
        if (options.any { it.value == shown }) return options
        options.add(options.indexOfFirst { it.value == "custom" }, Option(shown, unlistedLabel(shown, strings, format)))
        return options
    }

    /** `unlistedReminderLabel`: the minutes in the largest unit that divides them. */
    fun unlistedLabel(value: String, strings: Strings, format: BirthdaysFormat): String {
        val m = minutes(value)
        val a = abs(m)
        val (amount, unit) = when {
            a % 10080 == 0 -> a / 10080 to "week"
            a % 1440 == 0 -> a / 1440 to "day"
            a % 60 == 0 -> a / 60 to "hour"
            else -> a to "minute"
        }
        val duration = format.duration(amount, unit)
        return strings.t(if (m < 0) "birthdays.reminderAfterNoon" else "birthdays.reminderBefore", "duration" to duration)
    }

    /** `reminderReadText`: what the read view names, a custom one as a duration. */
    fun reminderText(b: Birthday, strings: Strings, format: BirthdaysFormat): String {
        val offset = storedOffset(b)
        if (offset == "custom") {
            val amount = b.reminderCustomAmount?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val unit = when (b.reminderCustomUnit ?: "days") {
                "minutes" -> "minute"
                "hours" -> "hour"
                "days" -> "day"
                "weeks" -> "week"
                else -> "minute"
            }
            return format.duration(amount, unit)
        }
        return reminderOptions(b, strings, format).firstOrNull { it.value == offset }?.label.orEmpty()
    }

    /** The editor's reminder fields. */
    data class Reminder(val offset: String, val amount: String, val unit: String)

    /**
     * `reminderToSave`: with "custom" the amount must be 1 to 999; with a preset an invalid hidden
     * amount and unit stay out instead of failing the save. Returns the fields to send, or null
     * when the custom amount is invalid.
     */
    fun reminderToSave(r: Reminder): Map<String, String>? {
        val amount = r.amount.takeIf { Regex("^\\d{1,9}$").matches(it) }?.toInt()
        val valid = amount != null && amount in 1..REMINDER_AMOUNT_MAX
        if (r.offset == "custom") return if (valid) all(r) else null
        return if (valid) all(r) else mapOf("reminder_offset" to r.offset)
    }

    private fun all(r: Reminder) = mapOf("reminder_offset" to r.offset, "reminder_custom_amount" to r.amount, "reminder_custom_unit" to r.unit)

    /** `reminderOpensAdvanced`: the more section opens when the reminder is not the default. */
    fun reminderIsSpecial(b: Birthday?): Boolean = b != null && storedOffset(b).let { it != "" && it != NEW_REMINDER }

    // ── Name day ──

    /** `daysInNameDayMonth`, counted in the leap year 2000. */
    fun daysInMonth(month: Int?): Int = if (month == null || month !in 1..12) 0 else YearMonth.of(2000, month).lengthOfMonth()

    /** `normalizeNameDaySelection`: `MM-DD`, null for none, or incomplete. */
    data class NameDay(val value: String?, val complete: Boolean)

    fun normalizeNameDay(month: Int?, day: Int?): NameDay {
        if (month == null && day == null) return NameDay(null, true)
        if (month == null || day == null || day < 1 || day > daysInMonth(month)) return NameDay(null, false)
        return NameDay("%02d-%02d".format(month, day), true)
    }

    // ── Avatar ──

    /** `photoAvatar`: the entry's own picture, then the member's, then the member's colour, else neutral. */
    data class Avatar(val name: String, val color: String?, val picture: String?)

    fun avatar(b: Birthday): Avatar = when {
        b.photoData != null -> Avatar(b.name, b.familyAvatarColor ?: NEUTRAL, b.photoData)
        b.familyUserId != null -> Avatar(b.familyDisplayName ?: b.name, b.familyAvatarColor ?: NEUTRAL, b.familyAvatarData)
        else -> Avatar(b.name, NEUTRAL, null)
    }

    /** `AVATAR_FALLBACK_COLOR`-like neutral disc for people outside the household. */
    const val NEUTRAL = "#8E8E93"

    /** `sameNameKey`. */
    fun sameNameKey(name: String?): String = Normalizer.normalize(name.orEmpty(), Normalizer.Form.NFC).trim().lowercase()

    /** `fillContactLinkOptions`: every contact without a birthday, plus the linked one, by name. */
    fun linkable(candidates: ImportCandidates, current: Int?): List<ImportCandidate> =
        (candidates.withBirthday + candidates.withoutBirthday)
            .filter { !it.alreadyImported || it.id == current }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    // ── Errors (`errorSentence`) ──

    const val BIRTHDAY_GONE = "birthdays_birthday_not_found"
    val CONTACT_CHOICE_REFUSALS = setOf("birthdays_contact_linked", "birthdays_selected_contact_not_found")

    private val CASES = mapOf(
        BIRTHDAY_GONE to "birthdays.errorBirthdayGone",
        "birthdays_contact_linked" to "birthdays.errorContactLinked",
        "birthdays_selected_contact_not_found" to "contacts.errorContactGone",
        "contacts_contact_not_found" to "contacts.errorContactGone",
    )

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "settings.permReadOnlyBanner",
        "csrf_invalid" to "common.errorFormExpired",
    )

    fun errorKey(e: Throwable, fallback: String = "common.errorGeneric"): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.status == 403 -> REFUSALS[e.reason] ?: "common.errorNoPermission"
            e.reason != null && e.reason in CASES -> CASES.getValue(e.reason)
            e.status == 429 -> "common.errorRateLimited"
            e.operationUnsupported -> "android.error.unsupported"
            else -> fallback
        }
        else -> fallback
    }

    /** `isAlreadyDeleted(err, 'birthdays_birthday_not_found')`. */
    fun isGone(e: Throwable): Boolean = e is ApiProblem.Http && e.status != 403 && e.reason == BIRTHDAY_GONE
}
