package app.planner.routines

import app.planner.calendar.CalendarTasks
import app.planner.model.Task
import app.planner.model.TaskStatus
import app.planner.scheduler.Block
import app.planner.scheduler.BlockKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One line of the 23:30 approval sheet: a task block tomorrow that he can switch off. */
data class PlanRow(
    val taskId: Long,
    val title: String,
    val module: String?,
    val start: LocalTime,
    val end: LocalTime,
    /** Switching it off pushes it past its due day, so the sheet warns about it. */
    val dueByTomorrow: Boolean,
    /** An all-day calendar item the planner gave a time to. */
    val fromCalendar: Boolean = false,
)

/** A calendar event at a fixed time, or a commitment, as the note on tomorrow's plan lists it. */
data class FixedLine(val start: LocalTime, val end: LocalTime, val title: String)

/** Everything about a day besides its task blocks: the fixed events and the tasks still waiting for a time. */
data class DayFacts(val fixed: List<FixedLine> = emptyList(), val unplaced: List<String> = emptyList())

object TomorrowPlan {
    /** Tomorrow's blocks of tasks still to do, in start order. */
    fun rows(date: LocalDate, blocks: List<Block>, tasks: Map<Long, Task>, zone: ZoneId): List<PlanRow> =
        blocks
            .filter { it.date == date && it.kind == BlockKind.TASK }
            .sortedWith(compareBy<Block> { it.start }.thenBy { it.id })
            .mapNotNull { b ->
                val task = b.taskId?.let(tasks::get) ?: return@mapNotNull null
                if (task.status != TaskStatus.OPEN && task.status != TaskStatus.PARTIAL) return@mapNotNull null
                val due = task.dueAt?.atZone(zone)?.toLocalDate()
                PlanRow(task.id, task.title, task.module, b.start, b.end, dueByTomorrow = due != null && due <= date, fromCalendar = CalendarTasks.isCalendarTask(task))
            }

    /** What the notification and the sheet header say about the rows that are still switched on. */
    fun outline(rows: List<PlanRow>, excluded: Set<Long>, facts: DayFacts = DayFacts()): DayOutline {
        val kept = rows.filter { it.taskId !in excluded }
        val first = kept.firstOrNull()
        return DayOutline(kept.size, first?.start, first?.title, facts.fixed.size, facts.unplaced.size)
    }

    /**
     * Tasks still to do that have no block anywhere in the plan, and that [date] should carry: due on or before
     * it, or with no due date, and already available. The planner could not find them a time.
     */
    fun unplaced(tasks: Collection<Task>, placedTaskIds: Set<Long>, date: LocalDate, zone: ZoneId): List<Task> =
        tasks.filter { t ->
            val due = t.dueAt?.atZone(zone)?.toLocalDate()
            (t.status == TaskStatus.OPEN || t.status == TaskStatus.PARTIAL) &&
                t.id !in placedTaskIds &&
                (due == null || due <= date) &&
                (t.availableFrom == null || t.availableFrom.atZone(zone).toLocalDate() <= date)
        }.sortedWith(compareBy<Task> { it.dueAt ?: Instant.MAX }.thenByDescending { it.priority }.thenBy { it.title.lowercase() })

    /** Excluding a task from tomorrow makes it available from the day after, never earlier than it already was. */
    fun defer(task: Task, tomorrow: LocalDate, zone: ZoneId): Task {
        val from = tomorrow.plusDays(1).atStartOfDay(zone).toInstant()
        val current = task.availableFrom
        return task.copy(availableFrom = if (current != null && current > from) current else from)
    }

    /** The tasks to defer when he approves: the switched-off rows that are still on the plan. */
    fun deferrals(rows: List<PlanRow>, excluded: Set<Long>, tasks: Map<Long, Task>, tomorrow: LocalDate, zone: ZoneId): List<Task> =
        rows.filter { it.taskId in excluded }.mapNotNull { row -> tasks[row.taskId]?.let { defer(it, tomorrow, zone) } }

    private val PENDING_UNTIL: LocalTime = LocalTime.of(6, 0)

    /**
     * The day whose plan still waits for approval: the day after the evening the 23:30 notice went out
     * ([sentOn]), until 06:00 on that day or until it is approved. Null when nothing is waiting.
     */
    fun pendingFor(sentOn: LocalDate?, approvedFor: LocalDate?, now: Instant, zone: ZoneId): LocalDate? {
        if (sentOn == null) return null
        val planDate = sentOn.plusDays(1)
        if (approvedFor == planDate) return null
        val until = planDate.atTime(PENDING_UNTIL).atZone(zone).toInstant()
        return planDate.takeIf { now < until }
    }
}
