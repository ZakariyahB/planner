package app.planner.gcal.read

import app.planner.engine.changeset.EventTime
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.Task
import app.planner.engine.model.TaskStatus
import app.planner.engine.model.Tier
import app.planner.engine.ports.TodayBlock
import app.planner.engine.ports.WeekDay
import app.planner.gcal.plan.BlockMath
import app.planner.gcal.plan.ChangeBuilder
import app.planner.gcal.plan.PlanningContext
import app.planner.gcal.plan.Tiers
import java.time.LocalDate

/** Builds the Today and Week screen rows from synced events (Google Calendar is the source of truth for placement). */
object ScreenStates {
    private val CHUNK_SUFFIX = Regex("""\s*\((\d+)/(\d+)\)$""")

    fun blocks(ctx: PlanningContext, day: LocalDate, logs: List<ExecutionLog> = emptyList()): List<TodayBlock> {
        val anchors = ctx.effectiveAnchors(day)
        val maghrib = ctx.maghrib(day)
        return ctx.events
            .filter { e -> !ctx.isPrayer(e) && (e.time as? EventTime.Timed)?.start?.toLocalDate() == day }
            .map { e ->
                val t = e.time as EventTime.Timed
                val own = e.isPlannerBlock
                val covered = if (own) BlockMath.covered(t.start, t.end, anchors) else emptyList()
                val (i, n) = ChangeBuilder.chunkOf(e)
                val label = if (own && n > 1) "($i/$n)" else null
                TodayBlock(
                    ref = e.ref,
                    taskId = e.taskId,
                    title = if (label != null) e.title.replace(CHUNK_SUFFIX, "") else e.title,
                    calendarName = e.calendarName.trim(),
                    category = ctx.directory.get(e.ref.calendarId)?.category,
                    start = t.start,
                    end = t.end,
                    tier = Tiers.of(t.start, t.end, maghrib, ctx.settings),
                    prayerBufferMin = covered.size * ctx.settings.prayerSlotMin,
                    coveredPrayers = covered.map { it.prayer },
                    isFixed = !own,
                    chunkLabel = label,
                    execution = logs.firstOrNull { it.eventRef == e.ref && it.plannedStart == t.start }
                        ?: logs.firstOrNull { it.eventRef == e.ref },
                )
            }
            .sortedWith(compareBy({ it.start }, { it.end }, { it.title }))
    }

    /** Template tasks for [day] that have no block yet. */
    fun unscheduled(tasks: List<Task>, day: LocalDate): List<Task> =
        tasks.filter { it.assignedDay == day && it.status == TaskStatus.BACKLOG && it.calendarEventIds.isEmpty() }
            .sortedWith(compareBy({ it.earliestStart }, { it.title }))

    fun weekDay(ctx: PlanningContext, day: LocalDate, tasks: List<Task>, logs: List<ExecutionLog> = emptyList()): WeekDay {
        val blocks = blocks(ctx, day, logs)
        val tiers = blocks.filter { !it.isFixed }.map { it.tier }
        val waiting = unscheduled(tasks, day).size
        return WeekDay(
            date = day,
            blocks = blocks,
            endTime = blocks.maxOfOrNull { it.end }?.toLocalTime(),
            maxTier = tiers.maxOrNull() ?: Tier.NORMAL,
            flags = Tiers.dayFlags(tiers, ctx.anchors(listOf(day)).any { it.interpolated }, hasOverflow = false),
            overflowCount = waiting,
        )
    }
}
