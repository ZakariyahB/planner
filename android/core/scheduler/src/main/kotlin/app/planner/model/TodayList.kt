package app.planner.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the Today list shows before the scheduler assigns times (M2):
 * open/partial tasks due today, overdue, or with no due date, plus tasks already done today.
 * Unfinished tasks come first, then by due time, priority (high first) and title.
 */
fun tasksForToday(tasks: List<Task>, today: LocalDate, zone: ZoneId): List<Task> {
    fun dueDate(t: Task): LocalDate? = t.dueAt?.atZone(zone)?.toLocalDate()

    return tasks
        .filter { t ->
            val due = dueDate(t)
            when (t.status) {
                TaskStatus.OPEN, TaskStatus.PARTIAL -> due == null || due <= today
                TaskStatus.DONE -> due == today
                TaskStatus.DROPPED -> false
            }
        }
        .sortedWith(
            compareBy<Task> { it.status == TaskStatus.DONE }
                .thenBy { it.dueAt ?: Instant.MAX }
                .thenByDescending { it.priority }
                .thenBy { it.title.lowercase() },
        )
}
