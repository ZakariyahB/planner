package app.planner.gcal.plan

import app.planner.engine.changeset.Change
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Placement
import app.planner.engine.model.Task
import app.planner.engine.model.TaskStatus
import app.planner.engine.parse.CategoryRouter
import app.planner.engine.parse.Durations
import app.planner.engine.ports.ChangeDraft
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.NowProvider
import app.planner.engine.ports.ProposalOutcome
import app.planner.engine.ports.ReplanReason
import app.planner.engine.ports.TaskStore
import app.planner.engine.schedule.BlockFit
import app.planner.engine.schedule.BlockPlacer
import app.planner.engine.schedule.PlanRequest
import app.planner.engine.schedule.PlanResult
import app.planner.engine.schedule.PlanWarning
import app.planner.engine.schedule.PlanningEngine
import app.planner.engine.schedule.RolloverRequest
import app.planner.gcal.IdSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Builds ChangeSets for every kind of request (newplan 5.6, 5.7, 7). The engine works out every time; this class
 * resolves targets, asks the engine, turns its answer into changes and saves the proposal. It never writes to a calendar.
 */
class Proposer(
    private val loadContext: suspend (from: LocalDate, toExclusive: LocalDate) -> PlanningContext,
    private val engine: PlanningEngine,
    private val placer: BlockPlacer,
    private val tasks: TaskStore,
    private val changeSets: ChangeSetStore,
    private val now: NowProvider,
    private val clock: () -> Instant,
    private val ids: IdSource,
    /** Looks up an event that is not in the synced window (Google, or null when offline). */
    private val fetch: suspend (EventRef) -> EventSnapshot? = { null },
) {
    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    private fun today(): LocalDate = now.now().toLocalDate()

    private suspend fun context(vararg days: LocalDate?): PlanningContext {
        val all = days.filterNotNull() + today()
        return loadContext(all.min().minusDays(7), maxOf(all.max().plusDays(8), today().plusDays(36)))
    }

    private fun LocalDate.label(): String = format(dayFormat)

    // ---------------------------------------------------------------- propose

    /** Working state across the drafts of one request, so "push both lectures to Thursday" does not stack them. */
    private inner class Session(val ctx: PlanningContext) {
        val builder = ChangeBuilder(ctx, ids)
        val changes = mutableListOf<Change>()
        val notes = mutableListOf<String>()
        val violations = mutableListOf<String>()
        val warnings = mutableListOf<PlanWarning>()
        /** Events this request re-times or removes, and where they go (null when removed). */
        val moved = mutableMapOf<EventRef, Pair<LocalDateTime, LocalDateTime>?>()
        val added = mutableListOf<Placement>()

        fun fixedOn(day: LocalDate): List<FixedEvent> =
            ctx.fixedOn(day).mapNotNull { f ->
                if (f.ref !in moved) f else moved[f.ref]?.let { (s, e) -> f.copy(start = s, end = e) }
            } + moved.entries.mapNotNull { (ref, time) ->
                val snap = ctx.snapshot(ref) ?: return@mapNotNull null
                if (snap.isPlannerBlock || time == null || time.first.toLocalDate() != day || ctx.fixedOn(day).any { it.ref == ref }) null
                else FixedEvent(ref, snap.title, time.first, time.second)
            }

        fun keepOn(day: LocalDate): List<Placement> =
            ctx.blocksOn(day).filter { it.ref !in moved }.map { ctx.asPlacement(it) } +
                moved.mapNotNull { (ref, time) ->
                    val snap = ctx.snapshot(ref)?.takeIf { it.isPlannerBlock } ?: return@mapNotNull null
                    time?.takeIf { it.first.toLocalDate() == day }?.let { ctx.asPlacement(snap).copy(start = it.first, end = it.second) }
                } + added.filter { it.start.toLocalDate() == day }

        fun planOn(day: LocalDate, newTasks: List<Task>): PlanResult =
            onlyFor(
                newTasks,
                engine.plan(
                    PlanRequest(
                        days = listOf(day), tasks = newTasks, fixed = fixedOn(day), anchors = ctx.anchors(listOf(day)),
                        settings = ctx.settings, notBefore = if (day == today()) now.now() else null, keep = keepOn(day),
                    ),
                ),
            )
    }

    /** The engine returns kept placements with the new ones; only the placements of [tasks] are new work. */
    private fun onlyFor(tasks: List<Task>, result: PlanResult): PlanResult {
        val ids = tasks.map { it.id }.toSet()
        return result.copy(placements = result.placements.filter { it.taskId in ids }, overflow = result.overflow.filter { it.taskId in ids })
    }

    suspend fun propose(drafts: List<ChangeDraft>, source: ChangeSource, summary: String): ProposalOutcome {
        if (drafts.isEmpty()) return ProposalOutcome.NothingToDo("No changes were asked for.")
        val ctx = context(*drafts.map(::dayOf).toTypedArray())
        val s = Session(ctx)
        for (draft in drafts) {
            val choice = when (draft) {
                is ChangeDraft.AddTask -> { addTask(s, draft); null }
                is ChangeDraft.ScheduleTask -> { scheduleTask(s, draft); null }
                is ChangeDraft.MoveEvent -> moveEvent(s, draft)
                is ChangeDraft.EditEvent -> editEvent(s, draft)
                is ChangeDraft.RemoveEvent -> removeEvent(s, draft)
            }
            if (choice != null) return choice
        }
        return finish(s, source, summary)
    }

    private fun dayOf(d: ChangeDraft): LocalDate? = when (d) {
        is ChangeDraft.AddTask -> d.day
        is ChangeDraft.ScheduleTask -> d.day
        is ChangeDraft.MoveEvent -> d.toDay
        else -> null
    }

    private suspend fun addTask(s: Session, d: ChangeDraft.AddTask) {
        if (d.durationMin !in Durations.MIN_MINUTES..Durations.MAX_MINUTES) {
            s.violations += "${d.title}: ${d.durationMin} min is not a usable duration"
            return
        }
        val category = d.category ?: CategoryRouter.route(d.title)
        val base = Task(
            id = ids.next(), title = d.title.trim(), category = category, durationMin = d.durationMin,
            assignedDay = d.day, earliestStart = d.earliestStart, afterPrayer = d.afterPrayer, deadline = d.deadline,
            splittable = d.splittable, minChunkMin = s.ctx.settings.minChunkMin,
        )
        val days = d.day?.let(::listOf) ?: (0L..6L).map { today().plusDays(it) }
        for (day in days) {
            val task = base.copy(assignedDay = day)
            val plan = s.planOn(day, listOf(task))
            if (plan.placements.isEmpty()) continue
            tasks.upsert(listOf(task)) // the backlog holds the task now; its block waits for approval
            accept(s, plan, mapOf(task.id to task))
            return
        }
        tasks.upsert(listOf(base.copy(assignedDay = d.day)))
        s.notes += "${base.title} (${Durations.format(base.durationMin)}) is in the backlog; it does not fit on " +
            (d.day?.label() ?: "any of the next 7 days") + "."
    }

    private suspend fun scheduleTask(s: Session, d: ChangeDraft.ScheduleTask) {
        val task = tasks.get(d.taskId) ?: s.ctx.task(d.taskId)
        if (task == null) { s.violations += "No task with id ${d.taskId}"; return }
        if (task.status == TaskStatus.SCHEDULED && task.calendarEventIds.isNotEmpty()) {
            s.violations += "${task.title} is already on the calendar; move its block instead"
            return
        }
        val day = d.day ?: task.assignedDay ?: today()
        val planned = task.copy(assignedDay = day)
        val plan = s.planOn(day, listOf(planned))
        if (plan.placements.isEmpty()) {
            s.notes += "${task.title} (${Durations.format(task.durationMin)}) does not fit on ${day.label()}."
            return
        }
        accept(s, plan, mapOf(task.id to planned))
    }

    private fun accept(s: Session, plan: PlanResult, byId: Map<String, Task>) {
        val built = s.builder.forNewPlacements(plan.placements, byId)
        s.changes += built.changes
        s.violations += built.violations
        s.added += plan.placements
        s.warnings += plan.warnings
        plan.overflow.forEach { o ->
            val t = byId[o.taskId]
            s.notes += "${t?.title ?: o.taskId}: ${Durations.format(o.remainingMin)} did not fit" +
                (o.proposedDate?.let { "; it goes to the catch-up on ${it.label()}" } ?: "") + "."
        }
    }

    private sealed interface Resolved {
        data class Found(val event: EventSnapshot) : Resolved
        data class Choice(val outcome: ProposalOutcome.NeedsChoice) : Resolved
        data class Missing(val message: String) : Resolved
    }

    /**
     * newplan 7.2: resolve to an exact event. An exact ref wins; then the same event id in any calendar; then the
     * id read as a title ("Quran class"). More than one match asks which.
     */
    private suspend fun resolve(ctx: PlanningContext, ref: EventRef): Resolved {
        ctx.snapshot(ref)?.let { return Resolved.Found(it) }
        val byId = ctx.events.filter { it.ref.eventId == ref.eventId && (ref.calendarId.isBlank() || it.ref.calendarId == ref.calendarId) }
        if (byId.size == 1) return Resolved.Found(byId.single())
        val fetched = runCatching { fetch(ref) }.getOrNull()
        if (fetched != null) return Resolved.Found(fetched)
        val query = ref.eventId.trim().lowercase()
        val from = today()
        val byTitle = ctx.events.filter { e ->
            !ctx.isPrayer(e) && e.title.lowercase().contains(query) &&
                (ref.calendarId.isBlank() || e.ref.calendarId == ref.calendarId) && !endOf(e).toLocalDate().isBefore(from)
        }.sortedBy { startOf(it) }
        return when (byTitle.size) {
            0 -> Resolved.Missing("Could not find an event matching \"${ref.eventId}\"")
            1 -> Resolved.Found(byTitle.single())
            else -> Resolved.Choice(ProposalOutcome.NeedsChoice("Which \"${ref.eventId}\" do you mean?", byTitle))
        }
    }

    private suspend fun writableTarget(s: Session, ref: EventRef): Pair<EventSnapshot?, ProposalOutcome.NeedsChoice?> =
        when (val r = resolve(s.ctx, ref)) {
            is Resolved.Choice -> null to r.outcome
            is Resolved.Missing -> { s.violations += r.message; null to null }
            is Resolved.Found -> {
                val e = r.event
                if (!s.ctx.isWritable(e)) {
                    s.violations += "\"${e.title}\" is in ${e.calendarName.trim()}, which the app may not change"
                    null to null
                } else e to null
            }
        }

    private suspend fun moveEvent(s: Session, d: ChangeDraft.MoveEvent): ProposalOutcome? {
        val (target, choice) = writableTarget(s, d.ref)
        if (choice != null) return choice
        target ?: return null
        when (val t = target.time) {
            is EventTime.AllDay -> {
                val length = ChronoUnit.DAYS.between(t.start, t.endExclusive)
                s.changes += Change(
                    id = ids.next(), type = ChangeType.MOVE, target = target,
                    after = s.builder.draftOf(target).copy(time = EventTime.AllDay(d.toDay, d.toDay.plusDays(length))),
                    flags = s.builder.targetFlags(target), taskId = target.taskId,
                )
                s.moved[target.ref] = null
            }
            is EventTime.Timed -> if (target.isPlannerBlock) moveBlock(s, target, t, d) else {
                val start = d.toDay.atTime(d.earliestStart ?: t.start.toLocalTime())
                val end = start.plusMinutes(BlockMath.minutes(t.start, t.end).toLong())
                s.changes += s.builder.moveFixed(target, start, end, RecurrenceScope.THIS_INSTANCE)
                s.moved[target.ref] = start to end
            }
        }
        return null
    }

    private fun moveBlock(s: Session, target: EventSnapshot, t: EventTime.Timed, d: ChangeDraft.MoveEvent) {
        val task = s.ctx.task(target.taskId)
        s.moved[target.ref] = null // its old place is free while planning the new one
        val synthetic = blockTask(s.ctx, target, task, d.toDay, d.earliestStart)
        val p = s.planOn(d.toDay, listOf(synthetic)).placements.firstOrNull()
        if (p == null) {
            s.moved.remove(target.ref)
            s.notes += "${target.title} (${Durations.format(synthetic.durationMin)}) does not fit on ${d.toDay.label()}."
            return
        }
        s.changes += s.builder.move(target, p, task ?: synthetic)
        s.moved[target.ref] = p.start to p.end
    }

    /** One existing block as a task the engine can place: its work minutes, its task's constraints, never split. */
    private fun blockTask(ctx: PlanningContext, block: EventSnapshot, task: Task?, day: LocalDate, earliest: java.time.LocalTime? = null, order: Int = 0) = Task(
        id = "blk:${block.ref.eventId}",
        title = block.title,
        category = task?.category ?: ctx.directory.get(block.ref.calendarId)?.category ?: CalendarCategory.PERSONAL,
        durationMin = ctx.workMinutes(block),
        assignedDay = day,
        earliestStart = earliest ?: task?.earliestStart,
        afterPrayer = task?.afterPrayer,
        deadline = task?.deadline,
        splittable = false,
        priority = (task?.priority ?: 0) * 1000 - order,
        kind = task?.kind ?: app.planner.engine.model.TaskKind.DEEP,
    )

    private suspend fun editEvent(s: Session, d: ChangeDraft.EditEvent): ProposalOutcome? {
        val (target, choice) = writableTarget(s, d.ref)
        if (choice != null) return choice
        target ?: return null
        val calendarId = d.category?.let { cat ->
            s.ctx.calendarFor(cat) ?: run { s.violations += "No writable calendar named \"${cat.calendarName}\""; return null }
        } ?: target.ref.calendarId
        var time = target.time
        var note: String? = null
        val t = target.time
        val newDuration = d.durationMin
        if (newDuration != null) {
            if (newDuration !in Durations.MIN_MINUTES..Durations.MAX_MINUTES) {
                s.violations += "$newDuration min is not a usable duration"
                return null
            }
            if (t is EventTime.Timed) {
                val end = if (target.isPlannerBlock) {
                    when (val fit = placer.place(t.start, newDuration, s.ctx.effectiveAnchors(t.start.toLocalDate()), emptyList(), s.ctx.settings.prayerSlotMin)) {
                        is BlockFit.Fits -> fit.end
                        is BlockFit.Collides -> fit.end
                    }
                } else t.start.plusMinutes(newDuration.toLong())
                time = EventTime.Timed(t.start, end)
                val clash = (s.fixedOn(t.start.toLocalDate()).map { it.ref to it.title } +
                    s.ctx.blocksOn(t.start.toLocalDate()).filter { it.ref !in s.moved }.map { it.ref to it.title })
                    .filter { (ref, _) -> ref != target.ref }
                    .firstOrNull { (ref, _) ->
                        val other = s.ctx.snapshot(ref)?.time as? EventTime.Timed ?: return@firstOrNull false
                        BlockMath.overlaps(t.start, end, other.start, other.end)
                    }
                note = clash?.let { "Now overlaps ${it.second}" }
            } else {
                s.notes += "${target.title} is an all-day event; its length was left as it is."
            }
        }
        val after = EventDraft(calendarId, d.title?.trim()?.ifEmpty { null } ?: target.title, time, target.description, target.privateProps)
        if (after == s.builder.draftOf(target)) {
            s.notes += "${target.title} already looks like that."
            return null
        }
        val extra = (time as? EventTime.Timed)?.let { tt ->
            s.builder.placementFlags(null, Placement("", start = tt.start, end = tt.end, tier = Tiers.of(tt.start, tt.end, s.ctx.maghrib(tt.start.toLocalDate()), s.ctx.settings)))
        }.orEmpty()
        s.changes += s.builder.edit(target, after, d.scope, extra, note)
        (time as? EventTime.Timed)?.let { s.moved[target.ref] = it.start to it.end }
        return null
    }

    private suspend fun removeEvent(s: Session, d: ChangeDraft.RemoveEvent): ProposalOutcome? {
        val (target, choice) = writableTarget(s, d.ref)
        if (choice != null) return choice
        target ?: return null
        s.changes += s.builder.remove(target, d.scope)
        s.moved[target.ref] = null
        return null
    }

    private suspend fun finish(s: Session, source: ChangeSource, summary: String): ProposalOutcome {
        if (s.violations.isNotEmpty()) return ProposalOutcome.Rejected(s.violations.distinct())
        val notes = (s.notes + s.warnings.map(::describe)).distinct()
        if (s.changes.isEmpty()) return ProposalOutcome.NothingToDo(notes.joinToString(" ").ifEmpty { "Nothing needs to change." })
        val cs = ChangeSet(id = ids.next(), source = source, summary = summary, changes = s.changes.toList(), createdAt = clock(), notes = notes)
        changeSets.save(cs)
        return ProposalOutcome.Proposed(cs, s.warnings.toList())
    }

    private fun describe(w: PlanWarning): String = when (w) {
        is PlanWarning.DeadlineMissed -> "A task misses its deadline of ${w.deadline.label()}."
        is PlanWarning.AssumedGapKept -> "${w.title} is not on the calendar on ${w.date.label()}; its time is kept free."
        is PlanWarning.Note -> w.text
    }

    // ---------------------------------------------------------------- planDays

    suspend fun planDays(days: List<LocalDate>, source: ChangeSource): ProposalOutcome {
        val upcoming = days.distinct().sorted().filter { !it.isBefore(today()) }
        if (upcoming.isEmpty()) return ProposalOutcome.NothingToDo("Those days are in the past.")
        val ctx = context(upcoming.first(), upcoming.last())
        val todo = tasks.all().filter { it.status == TaskStatus.BACKLOG && it.calendarEventIds.isEmpty() && it.assignedDay in upcoming }
        if (todo.isEmpty()) return ProposalOutcome.NothingToDo("Nothing is waiting to be planned on ${upcoming.joinToString { it.label() }}.")
        val plan = engine.plan(
            PlanRequest(
                days = upcoming, tasks = todo, fixed = ctx.fixedIn(upcoming), anchors = ctx.anchors(upcoming), settings = ctx.settings,
                notBefore = if (upcoming.first() == today()) now.now() else null,
                keep = upcoming.flatMap { ctx.blocksOn(it) }.map { ctx.asPlacement(it) },
            ),
        )
        val s = Session(ctx)
        accept(s, onlyFor(todo, plan), todo.associateBy { it.id })
        val label = if (upcoming.size == 1) upcoming.first().label() else "${upcoming.first().label()} to ${upcoming.last().label()}"
        return finish(s, source, "Plan $label: ${s.changes.size} block${if (s.changes.size == 1) "" else "s"}")
    }

    // ---------------------------------------------------------------- rollover

    suspend fun rollover(targetWeekStart: LocalDate, source: ChangeSource, importTemplate: suspend (LocalDate, LocalDate) -> Unit): ProposalOutcome {
        val templateStart = targetWeekStart.minusDays(7)
        importTemplate(templateStart, targetWeekStart)
        val template = tasks.all().filter { t -> t.assignedDay?.let { !it.isBefore(templateStart) && it.isBefore(targetWeekStart) } == true }
        if (template.isEmpty()) return ProposalOutcome.NothingToDo("The week of ${templateStart.label()} has no template tasks to copy.")
        val ctx = context(templateStart, targetWeekStart.plusDays(6))
        val targetDays = (0L..6L).map { targetWeekStart.plusDays(it) }
        val templateDays = (0L..6L).map { templateStart.plusDays(it) }
        val expected = ctx.fixedIn(templateDays).filter { f -> ctx.snapshot(f.ref)?.recurringEventId != null } +
            ctx.fixedIn(templateDays).filter { it.assumed }
        val result = engine.rollover(
            RolloverRequest(
                templateTasks = template, templateWeekStart = templateStart, targetWeekStart = targetWeekStart,
                targetFixed = ctx.fixedIn(targetDays), expectedRecurring = expected.distinctBy { it.ref },
                targetAnchors = ctx.anchors(targetDays), settings = ctx.settings,
            ),
        )
        val past = result.plan.placements.filter { it.start.isBefore(now.now()) }.map { it.taskId }.toSet()
        tasks.upsert(result.tasks)
        val s = Session(ctx)
        s.notes += result.notes
        accept(s, result.plan.copy(placements = result.plan.placements.filter { it.taskId !in past }), result.tasks.associateBy { it.id })
        return finish(s, source, "Copy the template into the week of ${targetWeekStart.label()}: ${s.changes.size} blocks")
    }

    // ---------------------------------------------------------------- replan

    suspend fun replan(reason: ReplanReason, source: ChangeSource): ProposalOutcome {
        val ctx = context()
        return when (reason) {
            is ReplanReason.Overrun -> overrun(ctx, reason, source)
            is ReplanReason.StoppedEarly -> stoppedEarly(ctx, reason, source)
            is ReplanReason.UserEdited -> userEdited(ctx, reason, source)
            is ReplanReason.Free -> replanDay(
                Session(ctx), today(), source, "Replan today: ${reason.text}".take(120),
                keep = emptySet(), overrides = emptyMap(), movable = { _, _ -> true }, onlyConflicting = true,
            ) ?: ProposalOutcome.NothingToDo("Nothing today needs to move.")
        }
    }

    private suspend fun overrun(ctx: PlanningContext, r: ReplanReason.Overrun, source: ChangeSource): ProposalOutcome {
        val target = ctx.snapshot(r.ref) ?: runCatching { fetch(r.ref) }.getOrNull()
            ?: return ProposalOutcome.Rejected(listOf("Could not find that event"))
        val t = target.time as? EventTime.Timed ?: return ProposalOutcome.Rejected(listOf("${target.title} is an all-day event"))
        val s = Session(ctx)
        val day = t.start.toLocalDate()
        val newEnd: LocalDateTime
        if (target.isPlannerBlock) {
            if (!ctx.isWritable(target)) return ProposalOutcome.Rejected(listOf("${target.title} is in a calendar the app may not change"))
            val work = ctx.workMinutes(target) + r.extraMin
            newEnd = when (val fit = placer.place(t.start, work, ctx.effectiveAnchors(day), emptyList(), ctx.settings.prayerSlotMin)) {
                is BlockFit.Fits -> fit.end
                is BlockFit.Collides -> fit.end
            }
            s.changes += s.builder.edit(
                target, s.builder.draftOf(target).copy(time = EventTime.Timed(t.start, newEnd)), RecurrenceScope.THIS_INSTANCE,
                note = "${r.extraMin} more minutes, until ${newEnd.format(timeFormat)}",
            )
        } else {
            newEnd = t.end.plusMinutes(r.extraMin.toLong())
            s.notes += "Planned around ${target.title} running until ${newEnd.format(timeFormat)}."
        }
        val notBefore = maxOf(now.now(), t.start)
        return replanDay(
            s, day, source, "${target.title} ran over by ${Durations.format(r.extraMin)}: move what comes after",
            keep = setOf(target.ref), overrides = mapOf(target.ref to (t.start to newEnd)),
            movable = { block, start -> block.ref != target.ref && !start.isBefore(t.start) }, notBefore = notBefore,
            startedBefore = t.start, // nothing after the event's start could begin while it ran over
        ) ?: ProposalOutcome.NothingToDo("Nothing after ${target.title} needs to move.")
    }

    private suspend fun stoppedEarly(ctx: PlanningContext, r: ReplanReason.StoppedEarly, source: ChangeSource): ProposalOutcome {
        val target = ctx.snapshot(r.ref) ?: return ProposalOutcome.Rejected(listOf("Could not find that event"))
        val t = target.time as? EventTime.Timed ?: return ProposalOutcome.Rejected(listOf("${target.title} is an all-day event"))
        if (!ctx.isWritable(target)) return ProposalOutcome.Rejected(listOf("${target.title} is in a calendar the app may not change"))
        if (!r.at.isAfter(t.start) || !r.at.isBefore(t.end)) return ProposalOutcome.NothingToDo("${target.title} is not running at ${r.at.format(timeFormat)}.")
        val s = Session(ctx)
        val day = t.start.toLocalDate()
        val anchors = ctx.effectiveAnchors(day)
        val remaining = ctx.workMinutes(target) - BlockMath.workMinutes(t.start, r.at, anchors, ctx.settings.prayerSlotMin)
        s.changes += s.builder.edit(
            target, s.builder.draftOf(target).copy(time = EventTime.Timed(t.start, r.at)), RecurrenceScope.THIS_INSTANCE,
            note = "Stopped at ${r.at.format(timeFormat)}",
        )
        s.moved[target.ref] = t.start to r.at
        if (remaining >= Durations.MIN_MINUTES) {
            val task = ctx.task(target.taskId)
            val catchUp = catchUpFor(day, ctx)
            val rest = Task(
                id = ids.next(), title = baseTitle(target.title) + " (rest)",
                category = task?.category ?: ctx.directory.get(target.ref.calendarId)?.category ?: CalendarCategory.PERSONAL,
                durationMin = remaining, assignedDay = catchUp, deadline = task?.deadline, kind = task?.kind ?: app.planner.engine.model.TaskKind.DEEP,
                priority = task?.priority ?: 0,
            )
            tasks.upsert(listOf(rest))
            val plan = s.planOn(catchUp, listOf(rest))
            if (plan.placements.isEmpty()) {
                s.notes += "${Durations.format(remaining)} of ${baseTitle(target.title)} is left; it is in the backlog for ${catchUp.label()}."
            } else {
                s.notes += "${Durations.format(remaining)} of ${baseTitle(target.title)} is left; proposed for the catch-up on ${catchUp.label()}."
                accept(s, plan, mapOf(rest.id to rest))
            }
        }
        return finish(s, source, "Stop ${target.title} at ${r.at.format(timeFormat)}")
    }

    private suspend fun userEdited(ctx: PlanningContext, r: ReplanReason.UserEdited, source: ChangeSource): ProposalOutcome {
        val edited = r.refs.mapNotNull(ctx::snapshot).filter { it.time is EventTime.Timed }
        if (edited.isEmpty()) return ProposalOutcome.NothingToDo("Those events are not on the calendar any more.")
        val s = Session(ctx)
        edited.forEach { e -> constraintProblem(ctx, e)?.let { s.notes += it } }
        val days = edited.map { (it.time as EventTime.Timed).start.toLocalDate() }.distinct().sorted().filter { !it.isBefore(today()) }
        for (day in days) {
            val outcome = replanDay(
                s, day, source, "", keep = edited.map { it.ref }.toSet(), overrides = emptyMap(),
                movable = { _, _ -> true }, onlyConflicting = true, finish = false,
            )
            if (outcome != null) return outcome
        }
        return finish(s, source, "Your calendar edit${if (edited.size == 1) "" else "s"}: make room around ${edited.joinToString { it.title }}")
    }

    /** Why an edited block now breaks its task's constraints, or null. The edit itself stays as the user made it. */
    private fun constraintProblem(ctx: PlanningContext, e: EventSnapshot): String? {
        val t = e.time as? EventTime.Timed ?: return null
        val task = ctx.task(e.taskId) ?: return null
        val day = t.start.toLocalDate()
        task.earliestStart?.let { if (t.start.toLocalTime().isBefore(it)) return "${e.title} now starts at ${t.start.format(timeFormat)}, before ${it.format(timeFormat)}." }
        task.afterPrayer?.let { p ->
            val a = ctx.anchors(listOf(day)).firstOrNull { it.prayer == p } ?: return@let
            val slotEnd = BlockMath.at(a).plusMinutes(ctx.settings.prayerSlotMin.toLong())
            if (t.start.isBefore(slotEnd)) return "${e.title} now starts before ${Descriptions.prayerName(p)} is over (${slotEnd.format(timeFormat)})."
        }
        if (t.end.toLocalTime().isAfter(ctx.settings.hardCap) || t.end.toLocalDate().isAfter(day)) return "${e.title} now runs past ${ctx.settings.hardCap.format(timeFormat)}."
        return null
    }

    /**
     * Re-place the movable, not-yet-started blocks of [day] around everything else, from [notBefore] (now by default).
     * [keep] blocks stay exactly where they are ([overrides] give a kept event its new time). With [onlyConflicting],
     * only blocks that now overlap something move. Blocks that no longer fit go to the catch-up day if there is room.
     */
    private suspend fun replanDay(
        s: Session,
        day: LocalDate,
        source: ChangeSource,
        summary: String,
        keep: Set<EventRef>,
        overrides: Map<EventRef, Pair<LocalDateTime, LocalDateTime>>,
        movable: (EventSnapshot, LocalDateTime) -> Boolean,
        onlyConflicting: Boolean = false,
        notBefore: LocalDateTime = now.now(),
        /** Blocks that started before this stay put (they are under way). */
        startedBefore: LocalDateTime = notBefore,
        finish: Boolean = true,
    ): ProposalOutcome? {
        val ctx = s.ctx
        val startFloor = maxOf(notBefore, day.atStartOfDay())
        val underWay = maxOf(startedBefore, day.atStartOfDay())
        overrides.forEach { (ref, time) -> s.moved[ref] = time }
        val fixed = s.fixedOn(day)
        val blocks = ctx.blocksOn(day).filter { it.ref !in s.moved || it.ref in keep }
        val kept = mutableListOf<EventSnapshot>()
        val candidates = mutableListOf<EventSnapshot>()
        for (b in blocks) {
            val start = ctx.start(b)!!
            if (b.ref in keep || start.isBefore(underWay) || !movable(b, start) || !ctx.isWritable(b)) kept += b else candidates += b
        }
        val keptPlacements = kept.map { b -> overrides[b.ref]?.let { (st, en) -> ctx.asPlacement(b).copy(start = st, end = en) } ?: ctx.asPlacement(b) }
        val toMove = if (!onlyConflicting) candidates else {
            val anchors = ctx.effectiveAnchors(day)
            candidates.filter { b ->
                val t = b.time as EventTime.Timed
                fixed.any { BlockMath.overlaps(t.start, t.end, it.start, it.end) } ||
                    keptPlacements.any { BlockMath.overlaps(t.start, t.end, it.start, it.end) } ||
                    BlockMath.inPrayerSlot(t.start, anchors, ctx.settings.prayerSlotMin) != null
            }
        }
        val stay = candidates - toMove.toSet()
        val synthetic = toMove.mapIndexed { i, b -> blockTask(ctx, b, ctx.task(b.taskId), day, order = i) }
        val byId = toMove.associateBy { "blk:${it.ref.eventId}" }
        val plan = engine.plan(
            PlanRequest(
                days = listOf(day), tasks = synthetic, fixed = fixed, anchors = ctx.anchors(listOf(day)), settings = ctx.settings,
                notBefore = startFloor, keep = keptPlacements + stay.map { ctx.asPlacement(it) } + s.added.filter { it.start.toLocalDate() == day },
            ),
        )
        for (p in plan.placements) {
            val block = byId[p.taskId] ?: continue
            val t = block.time as EventTime.Timed
            if (p.start == t.start && p.end == t.end) continue
            s.changes += s.builder.move(block, p, ctx.task(block.taskId))
            s.moved[block.ref] = p.start to p.end
        }
        for (o in plan.overflow) {
            val block = byId[o.taskId] ?: continue
            val catchUp = catchUpFor(day, ctx)
            s.moved[block.ref] = null
            val there = if (catchUp != day) s.planOn(catchUp, listOf(blockTask(ctx, block, ctx.task(block.taskId), catchUp))).placements.firstOrNull() else null
            if (there != null) {
                s.changes += s.builder.move(block, there, ctx.task(block.taskId))
                s.moved[block.ref] = there.start to there.end
                s.notes += "${block.title} no longer fits on ${day.label()}; moved to the catch-up on ${catchUp.label()}."
            } else {
                s.moved.remove(block.ref)
                s.notes += "${block.title} no longer fits on ${day.label()} and there is no room on the catch-up day; it is left where it is."
            }
        }
        s.warnings += plan.warnings.filterIsInstance<PlanWarning.AssumedGapKept>()
        if (!finish) return null
        if (s.changes.isEmpty() && s.notes.isEmpty()) return ProposalOutcome.NothingToDo("Nothing on ${day.label()} needs to move.")
        return finish(s, source, summary)
    }

    private fun catchUpFor(day: LocalDate, ctx: PlanningContext): LocalDate = day.with(TemporalAdjusters.nextOrSame(ctx.settings.catchUpDay))

    private fun baseTitle(title: String) = title.replace(Regex("""\s*\(\d+/\d+\)$"""), "")

    private fun startOf(e: EventSnapshot): LocalDateTime = when (val t = e.time) {
        is EventTime.Timed -> t.start
        is EventTime.AllDay -> t.start.atStartOfDay()
    }

    private fun endOf(e: EventSnapshot): LocalDateTime = when (val t = e.time) {
        is EventTime.Timed -> t.end
        is EventTime.AllDay -> t.endExclusive.atStartOfDay()
    }
}
