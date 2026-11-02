package io.github.marcomow.yuvomi.feature.schedule

import io.github.marcomow.yuvomi.core.i18n.Strings
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The pure rules of `public/pages/schedule.js` and the utils it imports, without Android, so
 * they are tested on their own (docs/spec/schedule.md). Function names follow upstream's.
 */
object ScheduleRules {
    /** `SHIFT_COLOR_DEFAULT` (server) and `USER_COLOR_DEFAULT` (utils/color.js). */
    const val SHIFT_COLOR_DEFAULT = "#0891B2"

    /** `USER_COLORS` of utils/color.js: the colour picker's swatches. */
    val USER_COLORS = listOf("#78808C", "#3B82F6", "#16A34A", "#D97706", "#059669", "#D946EF", "#EF4444", "#0891B2", "#EA580C", "#EC4899")

    /** `DEFAULT_WEEKLY_HOURS`: the target until the member sets their own. */
    const val DEFAULT_WEEKLY_HOURS = 40

    /** `REMINDER_OFFSET_PRESETS` of utils/schedule-reminder-offset.js. */
    val REMINDER_PRESETS = listOf(0, 5, 10, 15, 30, 60, 120)
    const val MAX_REMINDER_MINUTES = 24 * 60
    const val MAX_CYCLE_LENGTH = 366

    // ── Labels and times ──

    /** `shortCodeAddsToName` of utils/shift-label.js. */
    fun shortCodeAddsToName(code: String?, name: String?): Boolean {
        val c = code.orEmpty().trim()
        val n = name.orEmpty().trim()
        if (c.isEmpty()) return false
        if (n.isEmpty()) return true
        if (c.lowercase() == n.lowercase()) return false
        if (!n.lowercase().startsWith(c.lowercase())) return true
        val next = n.getOrNull(c.length) ?: return false
        return next.isLetter()
    }

    /** `shiftTypeLabel`: "Code · Name", or the name alone when the code only repeats it. */
    fun label(type: ShiftType?): String {
        if (type == null) return ""
        return if (shortCodeAddsToName(type.shortCode, type.name)) "${type.shortCode.orEmpty().trim()} · ${type.name}" else type.name
    }

    fun time(value: String?): LocalTime? = value?.takeIf { it.length >= 4 }?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }

    private fun minutesOf(value: String): Int? = time(value)?.let { it.hour * 60 + it.minute }

    /** `shiftMinutes`: the length of a timed shift, over midnight when the end is not after the start. */
    fun shiftMinutes(type: ShiftType?): Int? {
        val start = type?.startTime?.let(::minutesOf) ?: return null
        var end = type.endTime?.let(::minutesOf) ?: return null
        if (end <= start) end += 24 * 60
        return end - start
    }

    /** `shiftTimeText` of utils/date-format.js: "22:00 - 06:00 +1", "06:00 - 06:00 +1 · 24 h", or "" untimed. */
    fun shiftTimeText(type: ShiftType?, strings: Strings, clock: (LocalTime) -> String): String {
        val start = time(type?.startTime) ?: return ""
        val end = time(type?.endTime) ?: return ""
        val fullDay = end == start
        val crossesDay = end < start
        val span = strings.t("calendar.dayRangeLabel", "from" to clock(start), "to" to clock(end))
        return span + (if (crossesDay || fullDay) " +1" else "") + (if (fullDay) " · 24 h" else "")
    }

    /** `clockLabel`: the span, or "All day". */
    fun clockLabel(type: ShiftType?, strings: Strings, clock: (LocalTime) -> String): String =
        shiftTimeText(type, strings, clock).ifEmpty { strings.t("schedule.allDay") }

    /** `formatHours`: one decimal at most, in the UI language. */
    fun formatHours(minutes: Int, strings: Strings): String {
        val format = NumberFormat.getNumberInstance(Locale.forLanguageTag(strings.locale)).apply { maximumFractionDigits = 1 }
        return strings.t("schedule.hoursValue", "value" to format.format(minutes / 60.0))
    }

    /** `overlayMeta`: the note and every overlay field with a value, one line. */
    fun overlayMeta(entry: Entry): String {
        val fields = entry.shiftType?.fields.orEmpty().filter { it.showInOverlay && !entry.fieldValues[it.id.toString()].isNullOrEmpty() }
        return (listOf(entry.note) + fields.map { "${it.name}: ${entry.fieldValues[it.id.toString()]}" }).filterNot { it.isNullOrEmpty() }.joinToString(" · ")
    }

    // ── Permissions (`readOnly`, `canWrite`, `canEditType`) ──

    data class Viewer(val me: Int, val readOnly: Boolean, val manageOthers: Boolean) {
        /** `canWrite`: the module right and the owner of the row. */
        fun canWrite(userId: Int): Boolean = !readOnly && (manageOthers || userId == me)

        /** `canEditType`: shift types and fields belong to the household; their creator or an admin changes them. */
        fun canEdit(createdBy: Int?): Boolean = !readOnly && (manageOthers || (createdBy != null && createdBy == me))
    }

    // ── People ──

    data class Ranked(val id: Int, val name: String, val sortOrder: Int?)

    private fun compareNamesNoCase(a: String, b: String): Int {
        fun fold(c: Int) = if (c in 65..90) c + 32 else c
        val left = a.codePoints().toArray().map(::fold)
        val right = b.codePoints().toArray().map(::fold)
        for (i in 0 until minOf(left.size, right.size)) if (left[i] != right[i]) return left[i] - right[i]
        return left.size - right.size
    }

    /** `compareMembers` of utils/member-order.js: position, then name, then id. */
    private val memberOrder = Comparator<Ranked> { a, b ->
        val pa = a.sortOrder
        val pb = b.sortOrder
        if (pa != null || pb != null) {
            if (pa == null) return@Comparator 1
            if (pb == null) return@Comparator -1
            if (pa != pb) return@Comparator pa - pb
        }
        compareNamesNoCase(a.name, b.name).takeIf { it != 0 } ?: (a.id - b.id)
    }

    /** `memberRanks`: each id's place in the household order; unknown ids after all known. */
    fun memberRanks(people: List<Ranked>): Map<Int, Int> = people.sortedWith(memberOrder).mapIndexed { i, p -> p.id to i }.toMap()

    fun ranked(accounts: List<Account>) = accounts.map { Ranked(it.id, it.displayName, it.sortOrder) }

    private fun rank(ranks: Map<Int, Int>, id: Int) = ranks[id] ?: Int.MAX_VALUE

    /** `patternsInMemberOrder`: the server's order within one person stays. */
    fun patternsInMemberOrder(patterns: List<Pattern>, accounts: List<Account>): List<Pattern> {
        val ranks = memberRanks(ranked(accounts))
        return patterns.sortedWith(compareBy<Pattern>({ rank(ranks, it.userId) }, { it.userId }))
    }

    /** `userName`: display name, then username, then the id. */
    fun userName(accounts: List<Account>, id: Int): String =
        accounts.firstOrNull { it.id == id }?.let { it.displayName.ifEmpty { it.username } }?.ifEmpty { null } ?: id.toString()

    /** `ownerIds`: household members and whoever already owns a plan (#1207). */
    fun ownerIds(people: List<Person>, data: ScheduleData): Set<Int> =
        people.map { it.id }.toSet() + data.patterns.map { it.userId } + data.overrides.map { it.userId } + data.extras.map { it.userId }

    /** `userOptions`: whom a form offers as owner; a member who is not an admin only themselves. */
    fun ownerOptions(data: ScheduleData, viewer: Viewer): List<Account> {
        val eligible = ownerIds(data.people, data)
        return data.users.filter { it.id in eligible && (viewer.manageOthers || it.id == viewer.me) }
    }

    /** `sharedSurname` of utils/initials.js. */
    fun sharedSurname(names: List<String>): String {
        if (names.size < 2) return ""
        val lasts = names.map { it.trim().split(Regex("\\s+")) }.map { if (it.size >= 2) it.last() else "" }
        val first = lasts.first()
        return if (first.isNotEmpty() && lasts.all { it == first }) first else ""
    }

    /** The lane head's short name: without the household's shared surname. */
    fun shortName(name: String, surname: String): String =
        if (surname.isNotEmpty() && name.trim().endsWith(" $surname")) name.trim().dropLast(surname.length + 1) else name.trim()

    // ── Shift types ──

    data class TypeGroup(val key: String, val types: List<ShiftType>)

    /** `shiftTypeGroups`: timed by start then name, all-day by name. */
    fun shiftTypeGroups(types: List<ShiftType>): List<TypeGroup> {
        val byName = Comparator<ShiftType> { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        val timed = types.filter { it.timed }.sortedWith(compareBy<ShiftType> { it.startTime }.then(byName))
        val allDay = types.filterNot { it.timed }.sortedWith(byName)
        return listOf(TypeGroup(GROUP_TIMED, timed), TypeGroup(GROUP_ALL_DAY, allDay)).filter { it.types.isNotEmpty() }
    }

    const val GROUP_TIMED = "timed"
    const val GROUP_ALL_DAY = "allDay"

    /** `availableTypeFields`: the household's fields not attached yet. */
    fun availableTypeFields(customFields: List<CustomField>, attachedIds: Collection<Int>): List<CustomField> =
        customFields.filter { it.id !in attachedIds }

    /** Shift types without the ones the dialog would offer as "free". */
    fun typeById(types: List<ShiftType>, id: Int?): ShiftType? = id?.let { i -> types.firstOrNull { it.id == i } }

    // ── Presets (`PRESET_TEMPLATES`, `presetShortCode`, `presetAlreadyThere`) ──

    data class Preset(val key: String, val shortCode: String, val start: String?, val end: String?, val color: String, val icon: String)

    private val SHARED = listOf(
        Preset("vacation", "V", null, null, "#78808C", "tree-palm"),
        Preset("sick", "S", null, null, "#EF4444", "thermometer"),
    )

    val TEMPLATES: Map<String, List<Preset>> = linkedMapOf(
        "work" to listOf(
            Preset("early", "E", "06:00", "14:00", "#0891B2", "sunrise"),
            Preset("late", "L", "14:00", "22:00", "#D946EF", "sunset"),
            Preset("night", "N", "22:00", "06:00", "#3B82F6", "moon"),
            Preset("day", "D", "08:00", "16:00", "#16A34A", "sun"),
            Preset("fullDay", "24", "10:00", "10:00", "#D97706", "clock"),
        ) + SHARED,
        "school" to listOf(
            Preset("period1", "P1", "08:00", "08:45", "#3B82F6", "book-open"),
            Preset("period2", "P2", "08:55", "09:40", "#059669", "book-open"),
            Preset("period3", "P3", "09:55", "10:40", "#EA580C", "book-open"),
            Preset("period4", "P4", "10:50", "11:35", "#EC4899", "book-open"),
            Preset("exam", "EX", "09:00", "11:00", "#D97706", "file-text"),
        ) + SHARED,
        "university" to listOf(
            Preset("lecture", "VL", "09:00", "10:30", "#3B82F6", "presentation"),
            Preset("seminar", "SE", "10:45", "12:15", "#059669", "users"),
            Preset("lab", "LAB", "13:00", "15:00", "#16A34A", "flask-conical"),
            Preset("exam", "EX", "09:00", "11:00", "#D97706", "file-text"),
        ) + SHARED,
    )

    /** `QUICKSTART_TEMPLATES`: template and its label key. */
    val QUICKSTART = listOf("work" to "schedule.templateWork", "school" to "schedule.templateSchool", "university" to "schedule.templateUniversity")

    val ALL_PRESETS: List<Preset> = TEMPLATES.values.flatten().associateBy { it.key }.values.toList()

    private val NUMERIC_CODES = mapOf("period1" to "1", "period2" to "2", "period3" to "3", "period4" to "4", "fullDay" to "24")

    fun presetShortCode(preset: Preset, strings: Strings): String {
        NUMERIC_CODES[preset.key]?.let { return it }
        val key = "schedule.presetCodes.${preset.key}"
        return if (strings.has(key)) strings.t(key) else preset.shortCode
    }

    fun presetName(preset: Preset, strings: Strings): String = strings.t("schedule.presets.${preset.key}")

    /** `presetAlreadyThere`: today's code, the old English one, the name, or icon and colour. */
    fun presetAlreadyThere(preset: Preset, types: List<ShiftType>, strings: Strings): Boolean {
        val codes = types.map { it.shortCode }.toSet()
        val names = types.map { it.name.trim().lowercase() }.toSet()
        val looks = types.map { "${it.icon.orEmpty()}|${it.color.uppercase()}" }.toSet()
        return presetShortCode(preset, strings) in codes || preset.shortCode in codes ||
            presetName(preset, strings).trim().lowercase() in names || "${preset.icon}|${preset.color.uppercase()}" in looks
    }

    /** `visibleQuickstartTemplates`: the household may hide templates (Settings). */
    fun visibleTemplates(hidden: List<String>): List<Pair<String, String>> = QUICKSTART.filter { it.first !in hidden }

    data class PresetGroup(val labelKey: String, val presets: List<Preset>)

    /** `shiftPresetOptgroups`: one group per visible template, the shared ones apart. */
    fun presetGroups(hidden: List<String>): List<PresetGroup> {
        val shared = SHARED.map { it.key }.toSet() + "exam"
        val groups = visibleTemplates(hidden).mapNotNull { (key, label) ->
            TEMPLATES.getValue(key).filter { it.key !in shared }.takeIf { it.isNotEmpty() }?.let { PresetGroup(label, it) }
        }
        return groups + PresetGroup("schedule.presetShared", ALL_PRESETS.filter { it.key in shared })
    }

    /** The presets a quick start would create: those not there yet. */
    fun quickStartPresets(template: String, types: List<ShiftType>, strings: Strings): List<Preset> =
        TEMPLATES[template].orEmpty().filterNot { presetAlreadyThere(it, types, strings) }

    // ── Planning ──

    /** `planningNeedsShiftTypes`: nothing to plan with and nothing planned. */
    fun planningNeedsShiftTypes(data: ScheduleData): Boolean =
        data.types.isEmpty() && data.patterns.isEmpty() && data.overrides.isEmpty() && data.extras.isEmpty()

    /** `patternDaysExceedingCycleLength`: 1-based positions that a shorter cycle would cut off. */
    fun daysExceedingCycle(positions: List<Int>, cycleLength: Int): Pair<Int, Int>? {
        val excluded = positions.filter { it >= cycleLength }
        if (excluded.isEmpty()) return null
        return (excluded.min() + 1) to (excluded.max() + 1)
    }

    /** `cycleDayNextDate`: the next date (from today) at 1-based [position] of the cycle. */
    fun cycleDayNextDate(anchor: LocalDate, cycleLength: Int, position: Int, today: LocalDate): LocalDate {
        if (cycleLength < 1) return anchor
        fun norm(v: Long) = ((v % cycleLength) + cycleLength) % cycleLength
        val target = norm((position - 1).toLong())
        val diff = ChronoUnit.DAYS.between(anchor, today)
        val delta = norm(target - norm(diff))
        return anchor.plusDays(diff + delta)
    }

    /** `cycleDayHeaderLabel`'s date: the next occurrence, or the first one when the window excludes it. */
    fun cycleDayDate(anchor: LocalDate, cycleLength: Int, validFrom: LocalDate?, validUntil: LocalDate?, position: Int, today: LocalDate): LocalDate {
        val next = cycleDayNextDate(anchor, cycleLength, position, today)
        return if ((validFrom != null && next < validFrom) || (validUntil != null && next > validUntil)) anchor.plusDays((position - 1).toLong()) else next
    }

    /** `windowsOverlap`: empty bounds are open. */
    fun windowsOverlap(aFrom: String?, aUntil: String?, bFrom: String?, bUntil: String?): Boolean {
        val aStart = aFrom?.ifEmpty { null } ?: "0000-01-01"
        val aEnd = aUntil?.ifEmpty { null } ?: "9999-12-31"
        val bStart = bFrom?.ifEmpty { null } ?: "0000-01-01"
        val bEnd = bUntil?.ifEmpty { null } ?: "9999-12-31"
        return aStart <= bEnd && bStart <= aEnd
    }

    /** `findOverlappingActivePattern`: another active plan of the same person whose window meets this one. */
    fun overlappingActivePattern(patterns: List<Pattern>, userId: Int, validFrom: String?, validUntil: String?, excludeId: Int? = null): Pattern? =
        patterns.firstOrNull { it.isActive && it.userId == userId && it.id != excludeId && windowsOverlap(it.validFrom, it.validUntil, validFrom, validUntil) }

    /** `comparePatternPriority`: newer `valid_from` first, an open one last, then the higher id. */
    private val patternPriority = Comparator<Pattern> { a, b ->
        val af = a.validFrom?.ifEmpty { null }
        val bf = b.validFrom?.ifEmpty { null }
        if (af != bf) {
            if (af == null) return@Comparator 1
            if (bf == null) return@Comparator -1
            return@Comparator if (af < bf) 1 else -1
        }
        b.id - a.id
    }

    /** `resolveWinningPatternId`: only when two active plans of one person apply today. */
    fun winningPatternId(patterns: List<Pattern>, userId: Int, dateKey: String): Int? {
        val candidates = patterns.filter {
            it.isActive && it.userId == userId &&
                (it.validFrom.isNullOrEmpty() || it.validFrom <= dateKey) && (it.validUntil.isNullOrEmpty() || it.validUntil >= dateKey)
        }
        if (candidates.size < 2) return null
        return candidates.sortedWith(patternPriority).first().id
    }

    enum class SaveStep { PATTERN, DAYS }

    /** `patternSaveOrder`: a shorter cycle writes the days first. */
    fun patternSaveOrder(oldLength: Int, newLength: Int): List<SaveStep> =
        if (newLength < oldLength) listOf(SaveStep.DAYS, SaveStep.PATTERN) else listOf(SaveStep.PATTERN, SaveStep.DAYS)

    /** `sameFieldValues`: same keys and values, in any order. */
    fun sameFieldValues(a: Map<String, String>, b: Map<String, String>): Boolean = a == b

    data class OverrideGroup(
        val userId: Int,
        val shiftTypeId: Int?,
        val note: String?,
        val fieldValues: Map<String, String>,
        val from: String,
        val to: String,
        val ids: List<Int>,
    ) {
        val key: String get() = "override:$userId:$from"
    }

    data class ExtraGroup(
        val userId: Int,
        val shiftTypeId: Int?,
        val note: String?,
        val reminderOffsetMinutes: Int?,
        val fieldValues: Map<String, String>,
        val from: String,
        val to: String,
        val ids: List<Int>,
    ) {
        val key: String get() = "extra:${ids.joinToString("-")}"
    }

    private fun nextDay(key: String): String = runCatching { LocalDate.parse(key).plusDays(1).toString() }.getOrDefault("")

    /** `overrideGroups`: consecutive days of one person with the same type, note and fields, as one row. */
    fun overrideGroups(overrides: List<Override>, accounts: List<Account>): List<OverrideGroup> {
        val ranks = memberRanks(ranked(accounts))
        val sorted = overrides.sortedWith(compareBy<Override>({ rank(ranks, it.userId) }, { it.userId }, { it.dateKey }))
        val groups = mutableListOf<OverrideGroup>()
        for (row in sorted) {
            val last = groups.lastOrNull()
            val consecutive = last != null && last.userId == row.userId && last.shiftTypeId == row.shiftTypeId &&
                last.note.orEmpty() == row.note.orEmpty() && sameFieldValues(last.fieldValues, row.fieldValues) && nextDay(last.to) == row.dateKey
            if (consecutive) {
                groups[groups.lastIndex] = last!!.copy(to = row.dateKey, ids = last.ids + row.id)
            } else {
                groups += OverrideGroup(row.userId, row.shiftTypeId, row.note, row.fieldValues, row.dateKey, row.dateKey, listOf(row.id))
            }
        }
        return groups
    }

    /** `extraGroups`: as [overrideGroups], with the reminder lead time as one more axis. */
    fun extraGroups(extras: List<Extra>, accounts: List<Account>): List<ExtraGroup> {
        val ranks = memberRanks(ranked(accounts))
        val sorted = extras.sortedWith(compareBy<Extra>({ rank(ranks, it.userId) }, { it.userId }, { it.dateKey }))
        val groups = mutableListOf<ExtraGroup>()
        for (row in sorted) {
            val last = groups.lastOrNull()
            val consecutive = last != null && last.userId == row.userId && last.shiftTypeId == row.shiftTypeId &&
                last.note.orEmpty() == row.note.orEmpty() && last.reminderOffsetMinutes == row.reminderOffsetMinutes &&
                sameFieldValues(last.fieldValues, row.fieldValues) && nextDay(last.to) == row.dateKey
            if (consecutive) {
                groups[groups.lastIndex] = last!!.copy(to = row.dateKey, ids = last.ids + row.id)
            } else {
                groups += ExtraGroup(row.userId, row.shiftTypeId, row.note, row.reminderOffsetMinutes, row.fieldValues, row.dateKey, row.dateKey, listOf(row.id))
            }
        }
        return groups
    }

    data class Span(val from: String, val to: String)

    /** `rangeDifference`: the parts of the old span outside the new one (0 to 2). */
    fun rangeDifference(oldFrom: String, oldTo: String, newFrom: String, newTo: String): List<Span> {
        fun add(key: String, days: Long) = LocalDate.parse(key).plusDays(days).toString()
        val spans = mutableListOf<Span>()
        if (oldFrom < newFrom) {
            val end = add(newFrom, -1).let { if (it < oldTo) it else oldTo }
            if (oldFrom <= end) spans += Span(oldFrom, end)
        }
        if (oldTo > newTo) {
            val start = add(newTo, 1).let { if (it > oldFrom) it else oldFrom }
            if (start <= oldTo) spans += Span(start, oldTo)
        }
        return spans
    }

    /** `rangeReversed`: date keys compare as text. */
    fun rangeReversed(from: String?, to: String?): Boolean = !from.isNullOrEmpty() && !to.isNullOrEmpty() && from > to

    // ── Statistics ──

    enum class StatsRange(val labelKey: String) {
        CURRENT("schedule.currentMonth"),
        MONTHS("schedule.selectedMonths"),
        CUSTOM("schedule.customRange"),
    }

    data class Bounds(val from: LocalDate, val to: LocalDate)

    fun monthBounds(month: YearMonth): Bounds = Bounds(month.atDay(1), month.atEndOfMonth())

    /** `statisticBounds`: null for an empty or reversed period. */
    fun statisticBounds(range: StatsRange, today: LocalDate, monthFrom: YearMonth?, monthTo: YearMonth?, from: LocalDate?, to: LocalDate?): Bounds? = when (range) {
        StatsRange.CURRENT -> monthBounds(YearMonth.from(today))
        StatsRange.MONTHS -> if (monthFrom == null || monthTo == null || monthFrom > monthTo) null else Bounds(monthFrom.atDay(1), monthTo.atEndOfMonth())
        StatsRange.CUSTOM -> if (from == null || to == null || from > to) null else Bounds(from, to)
    }

    data class TypeStat(val type: ShiftType, val count: Int, val minutes: Int, val hasHours: Boolean)

    data class Summary(val values: List<TypeStat>, val freeDays: Int) {
        val totalCount: Int get() = values.sumOf { it.count }
        val totalMinutes: Int get() = values.sumOf { it.minutes }
        val hourValues: List<TypeStat> get() = values.filter { it.hasHours }
    }

    /** `statisticsSummary`: entries without a type are free days. */
    fun summary(entries: List<Entry>): Summary {
        val byType = linkedMapOf<Int, TypeStat>()
        var free = 0
        for (entry in entries) {
            val type = entry.shiftType
            if (type == null) {
                free++
                continue
            }
            val item = byType[type.id] ?: TypeStat(type, 0, 0, false)
            val minutes = shiftMinutes(type)
            byType[type.id] = item.copy(count = item.count + 1, minutes = item.minutes + (minutes ?: 0), hasHours = item.hasHours || minutes != null)
        }
        val values = byType.values.sortedWith(compareByDescending<TypeStat> { it.count }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.type.name })
        return Summary(values, free)
    }

    data class Overtime(val over: Boolean, val excessMinutes: Int)

    /** `overtimeInfo`: the worst rolling 7-day window against the weekly target. */
    fun overtime(entries: List<Entry>, weeklyHours: Int): Overtime {
        val days = entries.mapNotNull { e ->
            runCatching { LocalDate.parse(e.dateKey).toEpochDay() }.getOrNull()?.let { it to (e.shiftType?.let(::shiftMinutes) ?: 0) }
        }.sortedBy { it.first }
        var start = 0
        var sum = 0
        var worst = 0
        for (end in days.indices) {
            sum += days[end].second
            while (days[start].first < days[end].first - 6) {
                sum -= days[start].second
                start++
            }
            worst = maxOf(worst, sum)
        }
        val excess = maxOf(0, worst - weeklyHours * 60)
        return Overtime(excess > 0, excess)
    }

    // ── Overview ──

    /** `WEEK_START_INDEX`: monday, sunday or saturday; Monday otherwise. */
    fun weekStart(pref: String?): DayOfWeek = when (pref) {
        "sunday" -> DayOfWeek.SUNDAY
        "saturday" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.MONDAY
    }

    /** `startOfLocalWeekKey`. */
    fun startOfWeek(date: LocalDate, first: DayOfWeek): LocalDate {
        val diff = (date.dayOfWeek.value - first.value + 7) % 7
        return date.minusDays(diff.toLong())
    }

    data class FetchRange(val entriesFrom: LocalDate, val from: LocalDate, val to: LocalDate)

    /** `overviewFetchRange`: one day before the week for a night shift that continues into it. */
    fun overviewFetchRange(cursor: LocalDate, first: DayOfWeek): FetchRange {
        val from = startOfWeek(cursor, first)
        return FetchRange(from.minusDays(1), from, from.plusDays(6))
    }

    /** `overviewVisibleDays`: the cursor alone in day mode, its week otherwise. */
    fun visibleDays(dayMode: Boolean, cursor: LocalDate, first: DayOfWeek): List<LocalDate> {
        if (dayMode) return listOf(cursor)
        val from = startOfWeek(cursor, first)
        return (0L until 7L).map { from.plusDays(it) }
    }

    /** `normalizeOverviewSelection`: in the household order of [eligible], unknown ids dropped. */
    fun normalizeSelection(raw: List<Int>, eligible: List<Int>): List<Int> = eligible.filter { it in raw.toSet() }

    /** `initialOverviewSelection`: the saved choice, or the member themselves. */
    fun initialSelection(saved: List<Int>?, eligible: List<Int>, ownId: Int?): List<Int> =
        if (saved != null) normalizeSelection(saved, eligible) else normalizeSelection(listOfNotNull(ownId), eligible)

    /** `isOvernightEntry`. */
    fun isOvernight(entry: Entry): Boolean {
        val type = entry.shiftType ?: return false
        val start = time(type.startTime) ?: return false
        val end = time(type.endTime) ?: return false
        return end <= start
    }

    fun isTimed(entry: Entry): Boolean = entry.shiftType?.timed == true

    data class LaneEntry(val entry: Entry, val continuation: Boolean = false) {
        /** Where the block starts and ends on its day, in minutes (`overviewEntryBlock`). */
        fun span(): Pair<Int, Int>? {
            val type = entry.shiftType ?: return null
            val start = time(type.startTime) ?: return null
            val end = time(type.endTime) ?: return null
            val startMin = if (continuation) 0 else start.hour * 60 + start.minute
            val endRaw = end.hour * 60 + end.minute
            val endMin = if (continuation) endRaw else if (endRaw <= startMin) 24 * 60 else endRaw
            return startMin to maxOf(endMin, startMin + 30)
        }
    }

    data class Lane(val userId: Int, val entries: List<LaneEntry>)
    data class DayLanes(val date: LocalDate, val lanes: List<Lane>)

    /** `buildOverviewLanes`: a fixed lane per person, a night shift continued on the next day. */
    fun buildLanes(days: List<LocalDate>, selected: List<Int>, entries: List<Entry>): List<DayLanes> {
        val byDayUser = linkedMapOf<String, MutableList<LaneEntry>>()
        for (e in entries) byDayUser.getOrPut("${e.dateKey}:${e.userId}") { mutableListOf() } += LaneEntry(e)
        for (e in entries) {
            if (!isOvernight(e)) continue
            val next = runCatching { LocalDate.parse(e.dateKey).plusDays(1).toString() }.getOrNull() ?: continue
            val key = "$next:${e.userId}"
            val kept = byDayUser[key].orEmpty().filter { it.entry.shiftType != null }
            byDayUser[key] = (kept + LaneEntry(e, continuation = true)).toMutableList()
        }
        fun start(l: LaneEntry): Int {
            if (l.continuation) return -1
            val t = time(l.entry.shiftType?.startTime) ?: return -1
            return t.hour * 60 + t.minute
        }
        return days.map { d ->
            DayLanes(d, selected.map { id -> Lane(id, byDayUser["$d:$id"].orEmpty().sortedBy(::start)) })
        }
    }

    /** `scheduleEntryMatchKey`: date, person, source and the source's own id. */
    fun entryKey(entry: Entry): String {
        val sourceId = when (entry.source) {
            Entry.SOURCE_PATTERN -> entry.patternDayId?.toString() ?: "p${entry.patternId}"
            Entry.SOURCE_OVERRIDE -> entry.overrideId.toString()
            else -> entry.extraId.toString()
        }
        return listOf(entry.dateKey, entry.userId, entry.source, sourceId).joinToString(":")
    }

    /** `overviewHolidaysOnDay`. */
    fun holidaysOn(holidays: List<Holiday>, date: LocalDate): List<Holiday> {
        val key = date.toString()
        return holidays.filter { it.startDate <= key && it.endDate >= key }
    }

    private const val MORNING_HOUR = 7
    private const val EARLY_HOUR = MORNING_HOUR - 2

    /**
     * `alignedHourScroll` of utils/time-grid.js, in hours: the top hour of the grid so the current
     * hour is fully in view, starting the day at 07:00 or the first block ([morning]), whichever
     * is earlier; before 05:00 the hour before now.
     */
    fun topHour(hour: Int, visibleHours: Int, morning: Int?): Int {
        val dayStart = (morning ?: MORNING_HOUR).coerceIn(0, MORNING_HOUR)
        val night = if (hour < EARLY_HOUR) maxOf(0, hour - 1) else dayStart
        return maxOf(0, maxOf(hour + 1 - visibleHours, minOf(dayStart, night))).coerceAtMost(24 - visibleHours.coerceAtLeast(1)).coerceAtLeast(0)
    }
}
