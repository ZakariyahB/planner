package app.planner.routines

import app.planner.model.Task
import app.planner.model.TaskStatus
import app.planner.model.Weeks
import app.planner.scheduler.Block
import app.planner.scheduler.BlockKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** The decisions behind the Sunday 19:00 review. The screens only show what these return. */
object SundayReview {
    data class WeekReport(
        val plannedMin: Int,
        val doneMin: Int,
        val plannedTasks: Int,
        val doneTasks: Int,
        /** Planned this week and still open or part done, so they carry over. Dropped tasks are a choice, not a miss. */
        val notDone: List<Task>,
    ) {
        /** Share of planned minutes that got done; null when nothing was planned. */
        val doneRatio: Double? get() = if (plannedMin == 0) null else doneMin.toDouble() / plannedMin
    }

    /** Planned against done for the week starting [weekMonday], from the blocks that were on the plan. */
    fun report(weekMonday: LocalDate, blocks: List<Block>, tasks: Map<Long, Task>): WeekReport {
        val weekEnd = weekMonday.plusDays(6)
        val inWeek = blocks
            .filter { it.kind == BlockKind.TASK && it.date in weekMonday..weekEnd }
            .sortedWith(compareBy<Block> { it.date }.thenBy { it.start })
            .mapNotNull { b -> b.taskId?.let(tasks::get)?.let { b to it } }
        val done = inWeek.filter { (_, t) -> t.status == TaskStatus.DONE }
        val planned = inWeek.map { (_, t) -> t }.distinctBy { it.id }
        return WeekReport(
            plannedMin = inWeek.sumOf { (b, _) -> b.minutes },
            doneMin = done.sumOf { (b, _) -> b.minutes },
            plannedTasks = planned.size,
            doneTasks = planned.count { it.status == TaskStatus.DONE },
            notDone = planned.filter { it.status == TaskStatus.OPEN || it.status == TaskStatus.PARTIAL },
        )
    }

    /**
     * The Monday of the week the latest review (the last Sunday 19:00 to have passed) is about, or null once it
     * has been opened. The card stays on Today until then. A review before [since], when the routines were first
     * switched on, is not offered.
     */
    fun pendingWeek(now: Instant, zone: ZoneId, opened: Set<LocalDate>, since: Instant? = null): LocalDate? {
        val today = now.atZone(zone).toLocalDate()
        var sunday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        var at = ZonedDateTime.of(sunday.atTime(RoutineSchedule.SUNDAY_REVIEW_AT), zone).toInstant()
        if (at > now) {
            sunday = sunday.minusWeeks(1)
            at = ZonedDateTime.of(sunday.atTime(RoutineSchedule.SUNDAY_REVIEW_AT), zone).toInstant()
        }
        if (since != null && at < since) return null
        val week = sunday.plusDays(1)
        return week.takeIf { it !in opened }
    }

    /**
     * Skipped check-in: on Monday the week still gets generated from the template (and the known deadlines are
     * already tasks). Null when it is not Monday or the review already generated it. Generation is idempotent,
     * so running it twice never duplicates anything.
     */
    fun catchUpWeek(today: LocalDate, generated: Set<LocalDate>): LocalDate? =
        if (today.dayOfWeek == DayOfWeek.MONDAY) Weeks.mondayOf(today).takeIf { it !in generated } else null
}
