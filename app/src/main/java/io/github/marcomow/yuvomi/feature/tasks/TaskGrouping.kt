package io.github.marcomow.yuvomi.feature.tasks

import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.api.model.TaskCategory
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** TASKS-R4, TASKS-R5. Port of `groupBy` and the sort in upstream's `public/pages/tasks.js`. */
object TaskGrouping {

    enum class Mode(val key: String) { CATEGORY("category"), DUE("due") }

    data class Group(val id: String, val labelKey: String?, val label: String?, val tasks: List<Task>)

    enum class DueBucket(val labelKey: String) {
        OVERDUE("tasks.groupOverdue"),
        TODAY("tasks.groupToday"),
        THIS_WEEK("tasks.groupThisWeek"),
        NEXT_WEEK("tasks.groupNextWeek"),
        LATER("tasks.groupLater"),
        NO_DATE("tasks.groupNoDate"),
    }

    fun bucket(dueDate: String?, today: LocalDate): DueBucket {
        val due = HouseholdTime.date(dueDate) ?: return DueBucket.NO_DATE
        val diff = ChronoUnit.DAYS.between(today, due)
        return when {
            diff < 0 -> DueBucket.OVERDUE
            diff == 0L -> DueBucket.TODAY
            diff <= 3 -> DueBucket.THIS_WEEK
            diff <= 7 -> DueBucket.NEXT_WEEK
            else -> DueBucket.LATER
        }
    }

    /**
     * Groups in [mode]. The server's order is kept within a group, except that
     * category groups put hand-placed tasks (`sort_order`) first, as the PWA's
     * manual sort does.
     */
    fun group(
        tasks: List<Task>,
        mode: Mode,
        categories: List<TaskCategory>,
        today: LocalDate,
        categoryLabel: (String) -> String,
    ): List<Group> = when (mode) {
        Mode.CATEGORY -> {
            val order = categories.associate { it.key to it.sortOrder }
            tasks.groupBy { it.category.ifBlank { "misc" } }
                .entries
                .sortedWith(compareBy<Map.Entry<String, List<Task>>>({ order[it.key] ?: Int.MAX_VALUE }, { categoryLabel(it.key) }))
                .map { (key, list) ->
                    val manual = list.withIndex().sortedWith(
                        compareBy<IndexedValue<Task>>({ it.value.sortOrder == null }, { it.value.sortOrder ?: 0 }, { it.index }),
                    ).map { it.value }
                    Group(key, null, categoryLabel(key), manual)
                }
        }
        Mode.DUE -> {
            val byBucket = tasks.groupBy { bucket(it.dueDate, today) }
            DueBucket.entries.mapNotNull { b -> byBucket[b]?.let { Group(b.name, b.labelKey, null, it) } }
        }
    }
}
