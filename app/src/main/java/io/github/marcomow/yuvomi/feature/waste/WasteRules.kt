package io.github.marcomow.yuvomi.feature.waste

import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The decisions of `public/pages/waste.js` that need no screen: summaries, the upcoming split,
 * the onboarding, the FAB, the schedule anchors, the import wizard's decisions and the bodies the
 * page sends (docs/spec/waste.md). Pure Kotlin, unit tested.
 */
object WasteRules {
    const val UPCOMING_WINDOW_DAYS = 90L
    const val TYPE_NAME_MAX = 100
    const val NOTE_MAX = 500
    const val SOURCE_NAME_MAX = 150
    const val INTERVAL_MAX = 52
    const val URL_MIN_INTERVAL = 60
    const val URL_MAX_INTERVAL = 43_200
    const val URL_DEFAULT_INTERVAL = 1440
    const val REMINDER_MAX_OFFSET = 14

    val WEEKDAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    private val WEEKDAY_KEYS = mapOf(
        "MO" to "waste.weekdayMon", "TU" to "waste.weekdayTue", "WE" to "waste.weekdayWed", "TH" to "waste.weekdayThu",
        "FR" to "waste.weekdayFri", "SA" to "waste.weekdaySat", "SU" to "waste.weekdaySun",
    )
    val ORDINALS = listOf(1, 2, 3, 4, -1)
    private val ORDINAL_KEYS = mapOf(1 to "waste.ordinalFirst", 2 to "waste.ordinalSecond", 3 to "waste.ordinalThird", 4 to "waste.ordinalFourth", -1 to "waste.ordinalLast")

    fun weekdayKey(code: String): String? = WEEKDAY_KEYS[code]
    fun ordinalKey(n: Int): String? = ORDINAL_KEYS[n]

    /** `TYPE_PRESETS`, coloured from the shared palette. */
    data class Preset(val key: String, val icon: String, val color: String) {
        val labelKey: String get() = "waste.preset${key.replaceFirstChar { it.uppercase() }}"
    }

    val PRESETS = listOf(
        Preset("general", "trash-2", "#78808C"),
        Preset("recycling", "recycle", "#3B82F6"),
        Preset("organic", "leaf", "#16A34A"),
        Preset("paper", "newspaper", "#D97706"),
        Preset("glass", "wine", "#059669"),
        Preset("bulky", "armchair", "#D946EF"),
    )

    /** `USER_COLORS`, the type colour palette (`WASTE_TYPE_COLORS`). */
    val COLORS = listOf("#78808C", "#3B82F6", "#16A34A", "#D97706", "#059669", "#D946EF", "#EF4444", "#0891B2", "#EA580C", "#EC4899")

    // ── Dates ──

    private fun dayOf(code: String): DayOfWeek = DayOfWeek.of(WEEKDAYS.indexOf(code) + 1)

    fun weekdayCodeOf(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.value - 1]

    /** `nthWeekdayOfMonthLocal()`: the nth (1-4) or last (-1) weekday of a month. */
    fun nthWeekdayOfMonth(year: Int, month: Int, code: String, ordinal: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        val day = dayOf(code)
        return if (ordinal == -1) first.with(TemporalAdjusters.lastInMonth(day))
        else first.with(TemporalAdjusters.dayOfWeekInMonth(ordinal, day))
    }

    /** `nearestOrdinalAnchorDateKey()`: the next day on or after [today] that lies on the rule itself. */
    fun nearestOrdinalAnchor(ordinal: Int, code: String, today: LocalDate): LocalDate {
        val inMonth = nthWeekdayOfMonth(today.year, today.monthValue, code, ordinal)
        if (!inMonth.isBefore(today)) return inMonth
        val next = today.plusMonths(1)
        return nthWeekdayOfMonth(next.year, next.monthValue, code, ordinal)
    }

    // ── Summaries ──

    /** `recurrenceSummary()`. */
    fun recurrenceSummary(s: WasteSchedule, strings: Strings): String = when (s.recurrenceKind) {
        "weekly" -> {
            val days = s.weekdays.orEmpty().split(',').filter { it.isNotBlank() }.joinToString(", ") { code -> weekdayKey(code)?.let(strings::t) ?: code }
            if (s.interval > 1) strings.t("waste.summaryWeeklyInterval", "interval" to s.interval, "days" to days)
            else strings.t("waste.summaryWeekly", "days" to days)
        }
        "monthly_ordinal_weekday" -> {
            val ordinal = s.monthDay?.let(::ordinalKey)?.let(strings::t) ?: s.monthDay.toString()
            val weekday = s.weekdays?.let(::weekdayKey)?.let(strings::t) ?: s.weekdays.orEmpty()
            if (s.interval > 1) strings.t("waste.summaryOrdinalWeekdayInterval", "interval" to s.interval, "ordinal" to ordinal, "weekday" to weekday)
            else strings.t("waste.summaryOrdinalWeekday", "ordinal" to ordinal, "weekday" to weekday)
        }
        else -> {
            val day = if (s.monthDay == -1) strings.t("waste.monthDayLastDay") else strings.t("waste.summaryMonthDayN", "day" to (s.monthDay ?: 1))
            if (s.interval > 1) strings.t("waste.summaryMonthlyInterval", "interval" to s.interval, "day" to day)
            else strings.t("waste.summaryMonthly", "day" to day)
        }
    }

    // ── Upcoming ──

    data class Split(val primary: List<WasteOccurrence>, val rest: List<WasteOccurrence>)

    /** `splitUpcomingByType()`: each type's next pickup first, everything after it folded away. */
    fun splitUpcoming(occurrences: List<WasteOccurrence>): Split {
        val seen = HashSet<Int>()
        val (primary, rest) = occurrences.partition { seen.add(it.typeId) }
        return Split(primary, rest)
    }

    /** `deepLinkNeedsExpand()`. */
    fun deepLinkNeedsExpand(occurrences: List<WasteOccurrence>, typeId: Int?, date: String?): Boolean =
        typeId != null && date != null && splitUpcoming(occurrences).rest.any { it.typeId == typeId && it.dateKey == date }

    fun hasScheduleOrigin(o: WasteOccurrence) = o.origins.any { it.kind == "schedule" }
    fun oneOffOrigin(o: WasteOccurrence) = o.origins.firstOrNull { it.kind == "one_off" }

    /** The original day of a moved series pickup (`originBadges`). */
    fun movedFrom(o: WasteOccurrence): String? = o.origins.firstOrNull { it.kind == "schedule" && it.originalDate != null }?.originalDate

    data class ScheduleOrigin(val scheduleId: Int, val originalDate: String)

    /** `findScheduleOrigin()`: the series and the day an override is filed under. */
    fun scheduleOrigin(o: WasteOccurrence): ScheduleOrigin? {
        val origin = o.origins.firstOrNull { it.kind == "schedule" } ?: return null
        return ScheduleOrigin(origin.scheduleId ?: return null, origin.originalDate ?: o.dateKey)
    }

    fun nextForSchedule(scheduleId: Int, occurrences: List<WasteOccurrence>): WasteOccurrence? =
        occurrences.firstOrNull { o -> o.origins.any { it.kind == "schedule" && it.scheduleId == scheduleId } }

    // ── Types ──

    /** `isUnscheduledType()`: active, no schedule and nothing upcoming. */
    fun isUnscheduled(type: WasteType, schedules: List<WasteSchedule>, occurrences: List<WasteOccurrence>): Boolean =
        !type.archived && schedules.none { it.typeId == type.id } && occurrences.none { it.typeId == type.id }

    /** `remainingTypePresets()`: by name in this language, or by icon and colour in any. */
    fun remainingPresets(types: List<WasteType>, label: (Preset) -> String): List<Preset> {
        val names = types.map { it.name.trim().lowercase() }.toSet()
        val looks = types.map { "${it.icon.orEmpty()}|${it.color.orEmpty().uppercase()}" }.toSet()
        return PRESETS.filter { p -> label(p).trim().lowercase() !in names && "${p.icon}|${p.color.uppercase()}" !in looks }
    }

    fun isOnboarding(types: List<WasteType>): Boolean = types.isEmpty()

    /** `fabIntent()`: with an active type the FAB adds a schedule, otherwise a type. */
    fun fabCreatesSchedule(types: List<WasteType>): Boolean = types.any { !it.archived }

    /** `defaultScheduleType()`: the first active type without a schedule, else the first active. */
    fun defaultScheduleType(types: List<WasteType>, schedules: List<WasteSchedule>): WasteType? {
        val active = types.filter { !it.archived }
        return active.firstOrNull { t -> schedules.none { it.typeId == t.id } } ?: active.firstOrNull()
    }

    /** `moveType()`: the two `sort_order` writes of a swap within the listed (scheduled) types. */
    fun moveSwap(listed: List<WasteType>, id: Int, up: Boolean): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val index = listed.indexOfFirst { it.id == id }
        val other = if (up) index - 1 else index + 1
        if (index == -1 || other !in listed.indices) return null
        val a = listed[index]
        val b = listed[other]
        return (a.id to b.sortOrder) to (b.id to a.sortOrder)
    }

    // ── Sources ──

    /** `sourceHealthBadgeInfo()`: needs mapping before error before needs refresh. */
    fun healthBadgeKey(source: WasteSource): String? = when {
        source.needsMapping -> "waste.sourceNeedsMappingBadge"
        source.lastError != null || source.lastErrorReason != null -> "waste.sourceErrorBadge"
        source.needsRefresh -> "waste.sourceNeedsRefreshBadge"
        else -> null
    }

    enum class SourceAction(val labelKey: String) {
        REVIEW("waste.reviewMappingAction"), REFRESH("waste.refreshNowAction"), REIMPORT("waste.reimportAction")
    }

    fun sourceAction(source: WasteSource): SourceAction = when {
        source.isUrl && source.needsMapping -> SourceAction.REVIEW
        source.isUrl -> SourceAction.REFRESH
        else -> SourceAction.REIMPORT
    }

    // ── The import wizard ──

    data class LabelChoice(val typeIds: Set<Int>, val createNew: Boolean, val newName: String)

    /** `defaultLabelSelection()`: remembered ignore, remembered types, the suggestion, else a new type. */
    fun defaultChoice(label: ImportLabel): LabelChoice = when {
        label.rememberedIgnored -> LabelChoice(emptySet(), false, label.originalLabel)
        label.rememberedTypeIds.isNotEmpty() -> LabelChoice(label.rememberedTypeIds.toSet(), false, label.originalLabel)
        label.suggestedTypeId != null -> LabelChoice(setOf(label.suggestedTypeId), false, label.originalLabel)
        else -> LabelChoice(emptySet(), true, label.originalLabel)
    }

    /** `unresolvedBlockingDiagnostics()`. */
    fun unresolvedBlocking(diagnostics: List<ImportDiagnostic>, skipped: Set<String>): List<ImportDiagnostic> =
        diagnostics.filter { it.severity == "blocking" && it.eventKey !in skipped }

    sealed interface Decisions {
        data class Ok(val mappings: JsonArray) : Decisions
        data class MissingName(val label: String) : Decisions
    }

    /** `buildMappingDecisions()`: ignored, types, a new type, or types plus a new type. */
    fun buildDecisions(choices: List<Pair<String, LabelChoice>>): Decisions {
        val out = mutableListOf<JsonObject>()
        for ((label, c) in choices) {
            if (c.typeIds.isEmpty() && !c.createNew) {
                out += buildJsonObject { put("normalized_label", label); put("ignored", true) }
                continue
            }
            val name = c.newName.trim()
            if (c.createNew && name.isEmpty()) return Decisions.MissingName(label)
            out += buildJsonObject {
                put("normalized_label", label)
                if (c.typeIds.isNotEmpty()) put("type_ids", buildJsonArray { c.typeIds.sorted().forEach { add(JsonPrimitive(it)) } })
                if (c.createNew) put("new_type", buildJsonObject { put("name", name) })
            }
        }
        return Decisions.Ok(JsonArray(out))
    }

    /** The commit body: decisions, skipped events, the digest; the file, the version or the name as the case needs. */
    fun commitBody(mappings: JsonArray, skipped: Set<String>, preview: ImportPreview, ics: String?, reimport: Boolean, name: String?): JsonObject = buildJsonObject {
        put("mappings", mappings)
        put("skip_event_keys", buildJsonArray { skipped.forEach { add(JsonPrimitive(it)) } })
        put("preview_digest", preview.digest?.let(::JsonPrimitive) ?: JsonNull)
        if (ics != null) put("ics", ics)
        if (reimport) put("expected_version", preview.expectedVersion?.let(::JsonPrimitive) ?: JsonNull) else put("name", name.orEmpty())
    }

    private val DIAGNOSTIC_KEYS = mapOf(
        "unsupported_rdate" to "waste.importDiagnosticUnsupportedRdate",
        "unbounded_recurrence" to "waste.importDiagnosticUnboundedRecurrence",
        "skipped_unparsable" to "waste.importDiagnosticSkippedUnparsable",
        "cancelled_excluded" to "waste.importDiagnosticCancelledExcluded",
        "missing_uid_fallback" to "waste.importDiagnosticMissingUidFallback",
        "duplicate_instance" to "waste.importDiagnosticDuplicateInstance",
    )

    /** `importDiagnosticText()`. */
    fun diagnosticText(d: ImportDiagnostic, strings: Strings): String {
        val key = DIAGNOSTIC_KEYS[d.code] ?: return strings.t("waste.importDiagnosticOther")
        return strings.t(key, "name" to (d.name ?: ""), "count" to (d.count ?: 0))
    }

    /** `mappingTypesChanged()`. */
    fun mappingChanged(mapping: WasteMapping, typeIds: Set<Int>): Boolean = mapping.typeIds.toSet() != typeIds

    private val PROFILE_STATUS_KEYS = mapOf(
        "applicable" to "waste.mappingProfileStatusApplicable",
        "unchanged" to "waste.mappingProfileStatusUnchanged",
        "unmatched_pattern" to "waste.mappingProfileStatusUnmatchedPattern",
        "unmatched_type" to "waste.mappingProfileStatusUnmatchedType",
        "ambiguous_type" to "waste.mappingProfileStatusAmbiguousType",
    )

    fun profileStatusKey(status: String): String? = PROFILE_STATUS_KEYS[status]

    /** `exportMappingProfileFile()`'s file name. */
    fun profileFileName(sourceName: String): String =
        "waste-mapping-profile-${sourceName.replace(Regex("[^a-zA-Z0-9]+"), "-").lowercase().ifEmpty { "source" }}.json"

    // ── Bodies ──

    /** The schedule form, as `openScheduleModal` reads it. */
    data class ScheduleForm(
        val typeId: Int? = null,
        val kind: String = "weekly",
        val weekdays: Set<String> = emptySet(),
        val monthDay: Int = 1,
        val lastDay: Boolean = false,
        val ordinal: Int = 1,
        val ordinalWeekday: String = "MO",
        val interval: Int = 1,
        val anchor: LocalDate = LocalDate.of(2026, 1, 1),
        val validUntil: LocalDate? = null,
        val active: Boolean = true,
    )

    fun scheduleForm(schedule: WasteSchedule?, typeId: Int?, today: LocalDate): ScheduleForm {
        if (schedule == null) return ScheduleForm(typeId = typeId, weekdays = setOf(weekdayCodeOf(today)), anchor = today)
        val anchor = runCatching { LocalDate.parse(schedule.anchorDate) }.getOrDefault(today)
        val ordinal = schedule.recurrenceKind == "monthly_ordinal_weekday"
        return ScheduleForm(
            typeId = schedule.typeId,
            kind = schedule.recurrenceKind,
            weekdays = if (schedule.recurrenceKind == "weekly") schedule.weekdays.orEmpty().split(',').filter { it in WEEKDAYS }.toSet() else setOf(weekdayCodeOf(anchor)),
            monthDay = if (!ordinal && schedule.monthDay != null && schedule.monthDay != -1) schedule.monthDay else 1,
            lastDay = !ordinal && schedule.monthDay == -1,
            ordinal = if (ordinal) schedule.monthDay ?: 1 else 1,
            ordinalWeekday = if (ordinal) schedule.weekdays ?: "MO" else "MO",
            interval = schedule.interval.coerceIn(1, INTERVAL_MAX),
            anchor = anchor,
            validUntil = schedule.validUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            active = schedule.active,
        )
    }

    /** The body of `POST`/`PUT /schedules`; null when no weekday is chosen for a weekly schedule. */
    fun scheduleBody(f: ScheduleForm): JsonObject? {
        val typeId = f.typeId ?: return null
        if (f.kind == "weekly" && f.weekdays.isEmpty()) return null
        return buildJsonObject {
            put("type_id", typeId)
            put("recurrence_kind", f.kind)
            put("anchor_date", f.anchor.toString())
            put("interval", f.interval.coerceIn(1, INTERVAL_MAX))
            put("valid_until", f.validUntil?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("active", f.active)
            when (f.kind) {
                "weekly" -> put("weekdays", buildJsonArray { WEEKDAYS.filter { it in f.weekdays }.forEach { add(JsonPrimitive(it)) } })
                "monthly_ordinal_weekday" -> {
                    put("month_day", f.ordinal)
                    put("weekdays", f.ordinalWeekday)
                }
                else -> put("month_day", if (f.lastDay) -1 else f.monthDay)
            }
        }
    }

    fun pickupBody(typeId: Int, date: LocalDate, note: String): JsonObject = buildJsonObject {
        put("type_id", typeId)
        put("date", date.toString())
        put("note", note.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
    }

    /** `HH:MM` for `<input type="time">`. */
    fun validTime(value: String): Boolean = Regex("""^([01]\d|2[0-3]):[0-5]\d$""").matches(value)
}
