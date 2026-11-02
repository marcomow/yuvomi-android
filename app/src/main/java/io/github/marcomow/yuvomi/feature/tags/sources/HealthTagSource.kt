package io.github.marcomow.yuvomi.feature.tags.sources

import io.github.marcomow.yuvomi.feature.health.Activity
import io.github.marcomow.yuvomi.feature.health.CycleDayLog
import io.github.marcomow.yuvomi.feature.health.CyclePeriod
import io.github.marcomow.yuvomi.feature.health.HealthActivity
import io.github.marcomow.yuvomi.feature.health.HealthApi
import io.github.marcomow.yuvomi.feature.health.HealthPrevention
import io.github.marcomow.yuvomi.feature.health.HealthRoutes
import io.github.marcomow.yuvomi.feature.health.LabReport
import io.github.marcomow.yuvomi.feature.health.Medication
import io.github.marcomow.yuvomi.feature.health.NutritionEntry
import io.github.marcomow.yuvomi.feature.health.PreventionRecord
import io.github.marcomow.yuvomi.feature.health.PreventionType
import io.github.marcomow.yuvomi.feature.health.Vital
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Health's part of the Tags view (TAGS-R2), the member's OWN records only (no `user_id`, so
 * never the data of someone in their care): hashtags in the note of a vital, medication,
 * preventive care record, lab report, activity, nutrition entry (the last year), and, when the
 * cycle area is on, of a period or cycle day. Each item opens its area.
 */
object HealthTagSource : TagSource {
    override val moduleId = "health"

    data class Data(
        val vitals: List<Vital> = emptyList(),
        val medications: List<Medication> = emptyList(),
        val prevention: List<PreventionRecord> = emptyList(),
        val preventionTypes: List<PreventionType> = emptyList(),
        val labs: List<LabReport> = emptyList(),
        val activities: List<Activity> = emptyList(),
        val nutrition: List<NutritionEntry> = emptyList(),
        val periods: List<CyclePeriod> = emptyList(),
        val cycleDays: List<CycleDayLog> = emptyList(),
    )

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val api = HealthApi(shell.connection.client)
        val today = shell.time.today()
        val data = coroutineScope {
            val vitals = async { api.vitals(null) }
            val meds = async { api.medications(null, "${today}T00:00", "${today}T23:59") }
            val prevention = async { api.preventionRecords(null) }
            val types = async { api.preventionTypes() }
            val labs = async { api.labs(null) }
            val activities = async { api.activities(null) }
            val nutrition = async { api.nutritionEntries(null, today.minusYears(1).toString()) }
            val cycle = async {
                if (api.cycleEnabled()) api.periods(null) to api.cycleLogs(null) else emptyList<CyclePeriod>() to emptyList()
            }
            Data(
                vitals.await(), meds.await(), prevention.await(), types.await(), labs.await(), activities.await(),
                nutrition.await(), cycle.await().first, cycle.await().second,
            )
        }
        return items(data, SourceText.of(shell))
    }

    fun items(data: Data, text: SourceText): List<TaggedItem> = buildList {
        fun add(key: String, title: String, date: String?, area: String, vararg texts: String?) {
            val tags = Hashtags.extract(*texts)
            if (tags.isNotEmpty()) {
                add(TaggedItem(moduleId, key, title, text.join(text.label("health.tabs.$area"), text.date(date)), tags, HealthRoutes.area(area)))
            }
        }
        data.vitals.forEach { add("vital:${it.id}", text.label("health.vitals.metric.${it.type}"), it.measuredAt, "vitals", it.note) }
        data.medications.forEach { add("med:${it.id}", it.name, null, "meds", it.name, it.dosageText, it.note) }
        data.prevention.forEach {
            val name = HealthPrevention.label(it, data.preventionTypes).ifEmpty { text.label("health.tabs.prevention") }
            add("prevention:${it.id}", name, it.givenOn, "prevention", it.name, it.provider, it.note)
        }
        data.labs.forEach { add("lab:${it.id}", it.labName?.takeIf { n -> n.isNotBlank() } ?: text.label("health.tabs.labs"), it.reportDate, "labs", it.labName, it.note) }
        data.activities.forEach {
            add("activity:${it.id}", if (it.type in HealthActivity.TYPES) text.label("health.activity.type.${it.type}") else it.type, it.performedAt, "activity", it.note)
        }
        data.nutrition.forEach { add("nutrition:${it.id}", it.title, it.consumedAt, "nutrition", it.title, it.note) }
        data.periods.forEach { add("period:${it.id}", text.label("health.tabs.cycle"), it.startDate, "cycle", it.note) }
        data.cycleDays.forEach { add("cycleday:${it.id}", text.label("health.tabs.cycle"), it.logDate, "cycle", it.note) }
    }
}
