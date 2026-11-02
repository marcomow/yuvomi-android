package io.github.marcomow.yuvomi.feature.waste

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The shapes of the routes under `server/routes/waste/` and of `server/services/waste-store.js` (docs/spec/waste.md). */

/** A `waste_types` row. [icon] is a Lucide name (`trash-2`, `recycle`, ...). */
@Serializable
data class WasteType(
    val id: Int,
    val name: String = "",
    val icon: String? = null,
    val color: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val archived: FlexBool = false,
)

/** A `waste_schedules` row. `weekdays` is `MO,TU`, or one code for the ordinal kind; `month_day` -1 is "last". */
@Serializable
data class WasteSchedule(
    val id: Int,
    @SerialName("type_id") val typeId: Int,
    @SerialName("recurrence_kind") val recurrenceKind: String = "weekly",
    @SerialName("anchor_date") val anchorDate: String = "",
    val interval: Int = 1,
    val weekdays: String? = null,
    @SerialName("month_day") val monthDay: Int? = null,
    @SerialName("valid_until") val validUntil: String? = null,
    val active: FlexBool = true,
)

/** A `waste_one_off_pickups` row. */
@Serializable
data class WastePickup(
    val id: Int,
    @SerialName("type_id") val typeId: Int,
    val date: String = "",
    val note: String? = null,
)

/** Where an occurrence comes from: a schedule, a one-off pickup or an imported source. */
@Serializable
data class OccurrenceOrigin(
    val kind: String = "",
    @SerialName("schedule_id") val scheduleId: Int? = null,
    @SerialName("one_off_id") val oneOffId: Int? = null,
    @SerialName("source_id") val sourceId: Int? = null,
    @SerialName("source_name") val sourceName: String? = null,
    @SerialName("original_date") val originalDate: String? = null,
    val moved: FlexBool = false,
)

/** `coalesceOccurrences()`: one pickup of one type on one day, with every origin. */
@Serializable
data class WasteOccurrence(
    val key: String = "",
    @SerialName("date_key") val dateKey: String = "",
    @SerialName("type_id") val typeId: Int = 0,
    @SerialName("type_name") val typeName: String? = null,
    @SerialName("type_icon") val typeIcon: String? = null,
    @SerialName("type_color") val typeColor: String? = null,
    val moved: FlexBool = false,
    val coalesced: FlexBool = false,
    val origins: List<OccurrenceOrigin> = emptyList(),
)

/** A `waste_sources` row with `needs_refresh`; `url` and the error fields only for writers. */
@Serializable
data class WasteSource(
    val id: Int,
    val kind: String = "file",
    val name: String = "",
    val url: String? = null,
    @SerialName("coverage_start") val coverageStart: String? = null,
    @SerialName("coverage_end") val coverageEnd: String? = null,
    @SerialName("last_success_at") val lastSuccessAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_error_reason") val lastErrorReason: String? = null,
    @SerialName("last_error_params") val lastErrorParams: JsonObject? = null,
    @SerialName("needs_mapping") val needsMapping: FlexBool = false,
    @SerialName("needs_refresh") val needsRefresh: FlexBool = false,
    @SerialName("refresh_interval_minutes") val refreshIntervalMinutes: Int? = null,
    val version: Int? = null,
    val mappings: List<WasteMapping> = emptyList(),
) {
    val isUrl: Boolean get() = kind == "url"
}

/** One label of a source and the types it feeds (#2273); an empty set is "ignored". */
@Serializable
data class WasteMapping(
    val id: Int,
    @SerialName("normalized_label") val normalizedLabel: String = "",
    @SerialName("original_label") val originalLabel: String = "",
    @SerialName("type_ids") val typeIds: List<Int> = emptyList(),
)

/** `buildImportPreview()`'s answer, plus `expected_version` on a re-import; `unchanged` for a URL source. */
@Serializable
data class ImportPreview(
    val unchanged: FlexBool = false,
    val digest: String? = null,
    val counts: ImportCounts = ImportCounts(),
    val diagnostics: List<ImportDiagnostic> = emptyList(),
    val labels: List<ImportLabel> = emptyList(),
    @SerialName("expected_version") val expectedVersion: Int? = null,
)

@Serializable
data class ImportCounts(val events: Int = 0, val candidates: Int = 0)

@Serializable
data class ImportDiagnostic(
    val severity: String = "info",
    val code: String = "",
    @SerialName("event_key") val eventKey: String? = null,
    val name: String? = null,
    val count: Int? = null,
)

@Serializable
data class ImportLabel(
    @SerialName("normalized_label") val normalizedLabel: String = "",
    @SerialName("original_label") val originalLabel: String = "",
    val count: Int = 0,
    @SerialName("suggested_type_id") val suggestedTypeId: Int? = null,
    @SerialName("remembered_type_ids") val rememberedTypeIds: List<Int> = emptyList(),
    @SerialName("remembered_ignored") val rememberedIgnored: FlexBool = false,
)

/** `{ added, changed, removed }` of a commit or refresh. */
@Serializable
data class ImportDiff(val added: Int = 0, val changed: Int = 0, val removed: Int = 0)

/** The answer of a commit, a URL source create and a refresh. */
@Serializable
data class SourceOutcome(
    val outcome: String? = null,
    val diff: ImportDiff? = null,
    val reason: String? = null,
    val params: JsonObject? = null,
)

/** `previewMappingProfileImport()`. */
@Serializable
data class ProfilePreview(
    val entries: List<ProfileEntry> = emptyList(),
    @SerialName("applicable_count") val applicableCount: Int = 0,
    @SerialName("profile_digest") val profileDigest: String? = null,
)

@Serializable
data class ProfileEntry(
    val pattern: String = "",
    @SerialName("type_name") val typeName: String = "",
    @SerialName("original_label") val originalLabel: String? = null,
    val status: String = "",
)

/** One row of `GET /reminder-settings`, the member's own. */
@Serializable
data class ReminderSetting(
    @SerialName("type_id") val typeId: Int,
    @SerialName("type_name") val typeName: String = "",
    val enabled: FlexBool = false,
    @SerialName("offset_days") val offsetDays: Int = 1,
    @SerialName("delivery_time") val deliveryTime: String = "08:00",
)

/** `GET /waste/feed`: the member's own feed, or `null` when it was never switched on. */
@Serializable
data class WasteFeed(val url: String = "", @SerialName("type_ids") val typeIds: List<Int>? = null)

/** Everything `loadData()` reads, kept together for the offline cache (ADR 0007). */
@Serializable
data class WasteData(
    val types: List<WasteType> = emptyList(),
    val schedules: List<WasteSchedule> = emptyList(),
    val occurrences: List<WasteOccurrence> = emptyList(),
    val sources: List<WasteSource> = emptyList(),
)
