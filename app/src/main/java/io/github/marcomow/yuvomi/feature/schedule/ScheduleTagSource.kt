package io.github.marcomow.yuvomi.feature.schedule

import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem

/**
 * Schedule's part of the Tags view (docs/spec/schedule.md, SCHED-R41): the free text of the page
 * is a shift type's name, a plan's name, the note of an override or extra shift, and the custom
 * field values of a cycle day, override or extra. Overrides and extras are grouped as the
 * Planning tab lists them, so each tagged row opens its own dialog.
 */
object ScheduleTagSource : TagSource {
    override val moduleId = "schedule"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val client = shell.connection.client
        val data = ScheduleApi(client).load(shell.time.today())
        return items(data, shell.container.strings.value ?: return emptyList())
    }

    /** The tagged items of [data]; pure, for the test. */
    fun items(data: ScheduleData, strings: Strings): List<TaggedItem> = buildList {
        val typeName = { id: Int? -> ScheduleRules.typeById(data.types, id)?.let(ScheduleRules::label) }
        for (type in data.types) {
            val tags = Hashtags.extract(type.name, type.shortCode)
            if (tags.isNotEmpty()) {
                add(TaggedItem(moduleId, "type:${type.id}", ScheduleRules.label(type), strings.t("schedule.shiftType"), tags, ScheduleRoutes.shiftType(type.id)))
            }
        }
        for (p in data.patterns) {
            val tags = Hashtags.extract(p.name, *p.days.flatMap { it.fieldValues.values }.toTypedArray())
            if (tags.isNotEmpty()) {
                add(TaggedItem(moduleId, "pattern:${p.id}", p.name, ScheduleRules.userName(data.users, p.userId), tags, ScheduleRoutes.pattern(p.id)))
            }
        }
        for (g in ScheduleRules.overrideGroups(data.overrides, data.users)) {
            val tags = Hashtags.extract(g.note, *g.fieldValues.values.toTypedArray())
            if (tags.isNotEmpty()) {
                val title = typeName(g.shiftTypeId) ?: strings.t("schedule.freeDay")
                add(TaggedItem(moduleId, g.key, title, subtitle(g.from, g.to, g.userId, data), tags, ScheduleRoutes.override(g.userId, g.from)))
            }
        }
        for (g in ScheduleRules.extraGroups(data.extras, data.users)) {
            val tags = Hashtags.extract(g.note, *g.fieldValues.values.toTypedArray())
            if (tags.isNotEmpty()) {
                val title = typeName(g.shiftTypeId) ?: strings.t("schedule.extraBadgeLabel")
                add(TaggedItem(moduleId, g.key, title, subtitle(g.from, g.to, g.userId, data), tags, ScheduleRoutes.extra(g.ids)))
            }
        }
    }

    private fun subtitle(from: String, to: String, userId: Int, data: ScheduleData): String =
        listOf(if (from == to) from else "$from – $to", ScheduleRules.userName(data.users, userId)).joinToString(" · ")
}
