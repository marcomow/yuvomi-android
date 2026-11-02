package io.github.marcomow.yuvomi.feature.schedule

import java.time.LocalDate

/** The shift type dialog (`shiftFields`, `shiftTypeFieldsEditor`). */
data class ShiftTypeForm(
    val name: String = "",
    val shortCode: String = "",
    val color: String = ScheduleRules.SHIFT_COLOR_DEFAULT,
    val icon: String? = null,
    val start: String? = null,
    val end: String? = null,
    /** Attached fields in their order; null when the household has no fields to attach. */
    val fields: List<ScheduleBodies.AttachedField>? = null,
) {
    fun withPreset(preset: ScheduleRules.Preset, name: String, code: String) =
        copy(name = name, shortCode = code, start = preset.start, end = preset.end, color = preset.color, icon = preset.icon)

    companion object {
        fun of(type: ShiftType, hasCustomFields: Boolean) = ShiftTypeForm(
            name = type.name,
            shortCode = type.shortCode.orEmpty(),
            color = type.color,
            icon = type.icon,
            start = type.startTime?.take(5),
            end = type.endTime?.take(5),
            fields = if (hasCustomFields) type.fields.sortedBy { it.position }.map { ScheduleBodies.AttachedField(it.id, it.showInOverlay) } else null,
        )
    }
}

/** The pattern fields (`patternFields`); [cycleLength] is the number field's text. */
data class PatternForm(
    val userId: Int? = null,
    val name: String = "",
    val anchor: LocalDate,
    val cycleLength: String = "7",
    val active: Boolean = true,
    val validFrom: LocalDate? = null,
    val validUntil: LocalDate? = null,
) {
    val length: Int? get() = cycleLength.trim().toIntOrNull()?.takeIf { it in 1..ScheduleRules.MAX_CYCLE_LENGTH }

    companion object {
        fun of(p: Pattern, fallback: LocalDate) = PatternForm(
            userId = p.userId,
            name = p.name,
            anchor = runCatching { LocalDate.parse(p.anchorDate) }.getOrDefault(fallback),
            cycleLength = p.cycleLength.toString(),
            active = p.isActive,
            validFrom = p.validFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            validUntil = p.validUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }
}

/** One row of the cycle-day editor (`dayRowHtml`); positions are 0-based. */
data class DayRowForm(val key: Long, val position: Int, val shiftTypeId: Int?, val fieldValues: Map<String, String> = emptyMap())

/** The "Add entry" modes (`openScheduleCreateModal`). */
enum class EntryMode(val labelKey: String) {
    PATTERN("schedule.pattern"),
    REPLACE("schedule.override"),
    ADD("schedule.extraBadgeLabel"),
}

/** An override or extra over a range (`one-time-shared` fieldset, the edit dialogs). */
data class OccurrenceForm(
    val userId: Int? = null,
    val from: LocalDate,
    val to: LocalDate,
    val shiftTypeId: Int? = null,
    val note: String = "",
    val reminderOn: Boolean = false,
    val reminderMinutes: Int = 15,
    val fieldValues: Map<String, String> = emptyMap(),
) {
    /** `wireRangeFollow`: "To" follows "From" when "From" passes it. */
    fun withFrom(date: LocalDate) = copy(from = date, to = if (to < date) date else to)

    val reversed: Boolean get() = from > to
    val singleDay: Boolean get() = from == to
    val reminder: Int? get() = if (reminderOn) reminderMinutes else null

    companion object {
        fun ofOverride(g: ScheduleRules.OverrideGroup) = OccurrenceForm(
            userId = g.userId, from = LocalDate.parse(g.from), to = LocalDate.parse(g.to),
            shiftTypeId = g.shiftTypeId, note = g.note.orEmpty(), fieldValues = g.fieldValues,
        )

        fun ofExtra(g: ScheduleRules.ExtraGroup) = OccurrenceForm(
            userId = g.userId, from = LocalDate.parse(g.from), to = LocalDate.parse(g.to),
            shiftTypeId = g.shiftTypeId, note = g.note.orEmpty(),
            reminderOn = g.reminderOffsetMinutes != null, reminderMinutes = g.reminderOffsetMinutes ?: 15,
            fieldValues = g.fieldValues,
        )
    }
}

/** The checks the dialogs make before sending (`constraintErrors`, `reportReversedRange`). */
object ScheduleChecks {
    /** Field name to i18n key; empty when the form may be sent. */
    fun shiftType(form: ShiftTypeForm): Map<String, String> = buildMap {
        if (form.name.isBlank()) put("name", "common.required")
        if (form.shortCode.trim().length > 12) put("short_code", "common.invalidInput")
        if (form.start.isNullOrEmpty() != form.end.isNullOrEmpty()) put(if (form.start.isNullOrEmpty()) "start_time" else "end_time", "common.required")
    }

    fun pattern(form: PatternForm): Map<String, String> = buildMap {
        if (form.userId == null) put("user_id", "common.required")
        if (form.name.isBlank()) put("name", "common.required")
        val n = form.cycleLength.trim().toIntOrNull()
        when {
            n == null -> put("cycle_length", if (form.cycleLength.isBlank()) "common.required" else "common.fieldWholeNumber")
            n < 1 -> put("cycle_length", "common.fieldMin")
            n > ScheduleRules.MAX_CYCLE_LENGTH -> put("cycle_length", "common.fieldMax")
        }
        if (form.validFrom != null && form.validUntil != null && form.validFrom > form.validUntil) put("valid_until", "schedule.rangeOrderError")
    }

    /** [needsType]: an extra must have a shift type; an override may be a free day. */
    fun occurrence(form: OccurrenceForm, needsType: Boolean): Map<String, String> = buildMap {
        if (form.userId == null) put("user_id", "common.required")
        if (form.reversed) put("to", "schedule.rangeOrderError")
        if (needsType && form.shiftTypeId == null) put("shift_type_id", "schedule.shiftTypeRequiredError")
    }

    /** `cycleLengthTooShort`: the occupied days the shorter cycle would cut off, 1-based. */
    fun cycleConflict(rows: List<DayRowForm>, cycleLength: Int): Pair<Int, Int>? =
        ScheduleRules.daysExceedingCycle(rows.filter { it.shiftTypeId != null }.map { it.position }, cycleLength)
}
