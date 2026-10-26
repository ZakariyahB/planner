package app.planner.model

import app.planner.scheduler.Block
import java.time.LocalDate
import java.time.ZoneId

data class DayTaskList(val scheduled: List<Task>, val untimed: List<Task>) {
    val all: List<Task> get() = scheduled + untimed

    companion object {
        /** A due date is not a placement: show scheduled work on its actual day, without duplicating it elsewhere. */
        fun on(
            date: LocalDate,
            today: LocalDate,
            zone: ZoneId,
            tasks: Collection<Task>,
            blocks: Collection<Block>,
            catchUpTaskIds: Set<Long> = emptySet(),
        ): DayTaskList {
            val placed = blocks.mapNotNull { it.taskId }.toSet()
            val starts = blocks.filter { it.date == date }.mapNotNull { b -> b.taskId?.let { it to b.start } }.toMap()
            val eligible = tasks.filter { it.status != TaskStatus.DROPPED }
            val scheduled = eligible.filter { it.id in starts }.sortedBy { starts[it.id] }
            val untimed = eligible.filter { t ->
                val due = t.dueAt?.atZone(zone)?.toLocalDate()
                t.id !in placed && t.id !in catchUpTaskIds &&
                    (t.status != TaskStatus.DONE || due == date) &&
                    (t.availableFrom == null || t.availableFrom.atZone(zone).toLocalDate() <= date) &&
                    (due == date || (date == today && (due == null || due < today)))
            }.sortedWith(compareBy<Task> { it.dueAt }.thenByDescending { it.priority }.thenBy { it.title.lowercase() })
            return DayTaskList(scheduled, untimed)
        }
    }
}
