package app.planner.gcal.plan

import app.planner.engine.changeset.Change
import app.planner.engine.changeset.ChangeFlag
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.PlannerTags
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.Placement
import app.planner.engine.model.Task
import app.planner.engine.model.Tier
import app.planner.gcal.EventIds
import app.planner.gcal.IdSource
import java.time.LocalDateTime

/**
 * Turns engine placements and resolved targets into [Change]s: titles with chunk labels, descriptions (newplan 16),
 * planner tags, card flags and client event ids. Pure data; nothing is written.
 */
class ChangeBuilder(private val ctx: PlanningContext, private val ids: IdSource) {
    private val settings get() = ctx.settings

    class Built(val changes: List<Change>, val violations: List<String>)

    /**
     * Placements for tasks that are not on the calendar yet. With [app.planner.engine.model.Settings.convertAllDayOnSchedule],
     * the first chunk of a task imported from an all-day template event edits that event into a timed block (same id
     * and calendar, no duplicate); the other chunks are added.
     */
    fun forNewPlacements(placements: List<Placement>, tasks: Map<String, Task>): Built {
        val changes = mutableListOf<Change>()
        val violations = mutableListOf<String>()
        for (p in placements.sortedWith(compareBy({ it.start }, { it.taskId }))) {
            val task = tasks[p.taskId] ?: continue
            val source = task.sourceEvent?.let(ctx::snapshot)?.takeIf { it.time is EventTime.AllDay }
            if (p.chunkIndex == 1 && source != null && settings.convertAllDayOnSchedule && ctx.isWritable(source)) {
                changes += Change(
                    id = ids.next(), type = ChangeType.EDIT, target = source,
                    after = draft(task, p, source.ref.calendarId, source.description, source.privateProps),
                    flags = placementFlags(task, p) + targetFlags(source, toTimed = true),
                    taskId = task.id, chunkIndex = p.chunkIndex, note = note(p),
                )
                continue
            }
            val calendarId = source?.ref?.calendarId?.takeIf { ctx.directory.isWritable(it) } ?: ctx.calendarFor(task.category)
            if (calendarId == null) {
                violations += "No writable calendar named \"${task.category.calendarName}\" for ${task.title}"
                continue
            }
            val changeId = ids.next()
            changes += Change(
                id = changeId, type = ChangeType.ADD, after = draft(task, p, calendarId, null, emptyMap()),
                newEventId = EventIds.from("$changeId/${task.id}/${p.chunkIndex}"),
                flags = placementFlags(task, p), taskId = task.id, chunkIndex = p.chunkIndex, note = note(p),
            )
        }
        return Built(changes, violations)
    }

    /** Re-time one of the app's blocks (or a fixed event) to [p]'s time. Title and calendar are kept. */
    fun move(target: EventSnapshot, p: Placement, task: Task?, type: ChangeType = ChangeType.MOVE, scope: RecurrenceScope = RecurrenceScope.THIS_INSTANCE): Change {
        val description = if (target.isPlannerBlock && task != null) {
            Descriptions.merge(Descriptions.forBlock(task, p.copy(chunkIndex = chunkOf(target).first, chunkCount = chunkOf(target).second), settings), target.description)
        } else target.description
        val props = if (target.isPlannerBlock) bumpRev(target.privateProps) else target.privateProps
        return Change(
            id = ids.next(), type = type, target = target,
            after = EventDraft(target.ref.calendarId, target.title, EventTime.Timed(p.start, p.end), description, props),
            flags = placementFlags(task, p) + targetFlags(target), scope = scope,
            taskId = target.taskId, chunkIndex = chunkOf(target).first.takeIf { target.isPlannerBlock }, note = note(p),
        )
    }

    /** Re-time a fixed event without the engine: same length, new start. */
    fun moveFixed(target: EventSnapshot, start: LocalDateTime, end: LocalDateTime, scope: RecurrenceScope): Change = Change(
        id = ids.next(), type = ChangeType.MOVE, target = target,
        after = EventDraft(target.ref.calendarId, target.title, EventTime.Timed(start, end), target.description, target.privateProps),
        flags = targetFlags(target) + setOfNotNull(tierFlag(Tiers.of(start, end, ctx.maghrib(start.toLocalDate()), settings))),
        scope = scope, taskId = target.taskId,
    )

    fun edit(target: EventSnapshot, after: EventDraft, scope: RecurrenceScope, extraFlags: Set<ChangeFlag> = emptySet(), note: String? = null): Change =
        Change(
            id = ids.next(), type = ChangeType.EDIT, target = target, after = after,
            flags = targetFlags(target, toTimed = target.time is EventTime.AllDay && after.time is EventTime.Timed) + extraFlags,
            scope = scope, taskId = target.taskId, chunkIndex = chunkOf(target).first.takeIf { target.isPlannerBlock }, note = note,
        )

    fun remove(target: EventSnapshot, scope: RecurrenceScope): Change = Change(
        id = ids.next(), type = ChangeType.REMOVE, target = target, flags = targetFlags(target), scope = scope,
        taskId = target.taskId, chunkIndex = chunkOf(target).first.takeIf { target.isPlannerBlock },
    )

    fun draftOf(target: EventSnapshot): EventDraft =
        EventDraft(target.ref.calendarId, target.title, target.time, target.description, target.privateProps)

    fun placementFlags(task: Task?, p: Placement): Set<ChangeFlag> = buildSet {
        tierFlag(p.tier)?.let(::add)
        if (p.interpolatedPrayer) add(ChangeFlag.INTERPOLATED_PRAYER)
        val deadline = task?.deadline
        if (deadline != null && p.end.toLocalDate().isAfter(deadline)) add(ChangeFlag.MISSES_DEADLINE)
    }

    fun targetFlags(target: EventSnapshot, toTimed: Boolean = false): Set<ChangeFlag> = buildSet {
        if (!target.isPlannerBlock && target.time is EventTime.Timed) add(ChangeFlag.TOUCHES_FIXED_EVENT)
        if (target.organiserIsExternal) add(ChangeFlag.EXTERNAL_ORGANISER)
        if (target.recurringEventId != null) add(ChangeFlag.RECURRING)
        if (toTimed) add(ChangeFlag.CONVERTS_ALL_DAY)
    }

    private fun tierFlag(tier: Tier): ChangeFlag? = when (tier) {
        Tier.NORMAL -> null
        Tier.REST -> ChangeFlag.USES_REST_ZONE
        Tier.EXTENDED -> ChangeFlag.EXTENDED_DAY
        Tier.OVER_SOFT_LIMIT -> ChangeFlag.OVER_SOFT_LIMIT
    }

    private fun note(p: Placement): String? = Descriptions.bufferLine(p.prayerBufferMin, p.coveredPrayers)?.removeSuffix(".")

    private fun draft(task: Task, p: Placement, calendarId: String, previousDescription: String?, previousProps: Map<String, String>) = EventDraft(
        calendarId = calendarId,
        title = if (p.chunkCount > 1) "${task.title} (${p.chunkIndex}/${p.chunkCount})" else task.title,
        time = EventTime.Timed(p.start, p.end),
        description = Descriptions.merge(Descriptions.forBlock(task, p, settings), previousDescription),
        privateProps = bumpRev(previousProps) + mapOf(
            PlannerTags.PLANNER to "1",
            PlannerTags.TASK_ID to task.id,
            PlannerTags.CHUNK to "${p.chunkIndex}/${p.chunkCount}",
        ),
    )

    companion object {
        fun bumpRev(props: Map<String, String>): Map<String, String> =
            props + (PlannerTags.REV to ((props[PlannerTags.REV]?.toIntOrNull() ?: 0) + 1).toString())

        /** The chunk tag `i/n` (index, count); `1/1` when absent. */
        fun chunkOf(e: EventSnapshot): Pair<Int, Int> {
            val tag = e.privateProps[PlannerTags.CHUNK] ?: return 1 to 1
            val i = tag.substringBefore('/').toIntOrNull() ?: 1
            val n = tag.substringAfter('/', "1").toIntOrNull() ?: 1
            return i to n
        }
    }
}
