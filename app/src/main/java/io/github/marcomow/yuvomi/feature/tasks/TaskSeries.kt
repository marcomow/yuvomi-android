package io.github.marcomow.yuvomi.feature.tasks

import io.github.marcomow.yuvomi.core.api.model.Member
import io.github.marcomow.yuvomi.core.api.model.Task

/**
 * Series facts the detail and the list show beside the recurrence: a series another app
 * owns (TASKS-R42) and the rotation of a recurring task (TASKS-R43). Pure, so the rules
 * are unit tested against upstream's `public/utils/task-fields.js` and
 * `public/components/task-detail.js`.
 */
object TaskSeries {

    const val ROUND_ROBIN = "round_robin"

    /**
     * Upstream's `repeatsElsewhere`: is this task ticked off only in the app that owns its
     * rule? A rule Yuvomi could follow arrived as its own series and is ticked off here; any
     * other `external_rrule` makes the server answer `409 task_foreign_series`.
     */
    fun repeatsElsewhere(task: Task): Boolean {
        if (task.externalRrule.isNullOrBlank()) return false
        val rule = task.recurrenceRule
        return !(task.isRecurring && !rule.isNullOrBlank() && task.parentTaskId == null &&
            ruleFitsDue(rule, task.dueDate, task.dueTime))
    }

    /**
     * Upstream's `foreignRuleFitsDue`: Yuvomi clamps a monthly series on the 29th to 31st and
     * a yearly one on 29 February, RFC 5545 does not; an end only counts as a plain date on a
     * series without a time.
     */
    internal fun ruleFitsDue(rule: String?, dueDate: String?, dueTime: String? = null): Boolean {
        if (rule.isNullOrBlank() || dueDate.isNullOrBlank()) return false
        val day = dueDate.take(10).drop(5)
        val value = rule.replace(Regex("^RRULE:", RegexOption.IGNORE_CASE), "").uppercase()
        val until = Regex("(?:^|;)UNTIL=([^;]*)").find(value)?.groupValues?.get(1)
        if (until != null && (!dueTime.isNullOrBlank() || !Regex("^\\d{8}$").matches(until))) return false
        if (Regex("(^|;)FREQ=MONTHLY(;|$)").containsMatchIn(value) &&
            !Regex("(^|;)BYMONTHDAY=-1(;|$)").containsMatchIn(value)
        ) {
            return (day.drop(3).toIntOrNull() ?: 0) <= 28
        }
        if (Regex("(^|;)FREQ=YEARLY(;|$)").containsMatchIn(value)) return day != "02-29"
        return true
    }

    /**
     * Upstream's `rotationDetailRow`: null when there is no row, otherwise the next member's
     * name, or an empty string when the server names nobody the app can resolve (the mode
     * alone is shown then; a guessed name would read as a promise).
     */
    fun rotationNext(task: Task, members: List<Member>): String? {
        if (task.rotationMode != ROUND_ROBIN) return null
        if ((task.rotationPool?.size ?: 0) < 2) return null
        val next = task.rotationNextUser ?: return ""
        return members.firstOrNull { it.id == next }?.displayName.orEmpty()
    }
}
