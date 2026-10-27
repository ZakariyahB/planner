package app.planner.plan

import app.planner.calendar.AllDayTask
import app.planner.calendar.AllDayTasks
import app.planner.calendar.AllDayTicks
import app.planner.calendar.CalendarTasks
import app.planner.calendar.CalendarEvent
import app.planner.calendar.CalendarInfo
import app.planner.calendar.CalendarRepository
import app.planner.calendar.FixedEvent
import app.planner.calendar.FixedEvents
import app.planner.calendar.PlannerCalendarSync
import app.planner.calendar.PlannerSyncResult
import app.planner.calendar.PrayerTimes
import app.planner.data.BlockDao
import app.planner.data.ChangeLogDao
import app.planner.data.ChangeLogEntity
import app.planner.data.DayRulesRepository
import app.planner.data.SettingsRepository
import app.planner.data.SleepDao
import app.planner.data.TaskRepository
import app.planner.data.decodeDiffs
import app.planner.data.encodeDiffs
import app.planner.data.toBlock
import app.planner.data.toEntity
import app.planner.model.Prayer
import app.planner.model.Task
import app.planner.model.TaskStatus
import app.planner.model.Weeks
import app.planner.readiness.ReadinessBand
import app.planner.sleep.ReadinessRepository
import app.planner.scheduler.Actor
import app.planner.scheduler.AsleepSpan
import app.planner.scheduler.Block
import app.planner.scheduler.BlockDiff
import app.planner.scheduler.BlockKind
import app.planner.scheduler.BlockOrigin
import app.planner.scheduler.Commitments
import app.planner.scheduler.ChangeSet
import app.planner.scheduler.DayContext
import app.planner.scheduler.FreeWindow
import app.planner.scheduler.Op
import app.planner.scheduler.PinConflict
import app.planner.scheduler.PlanState
import app.planner.scheduler.Scheduler
import app.planner.scheduler.Validation
import app.planner.scheduler.apply
import app.planner.scheduler.validate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One day as the timeline draws it. */
data class DayView(
    val date: LocalDate,
    val fixed: List<FixedEvent>,
    val allDay: List<CalendarEvent>,
    /** The same all-day events as tasks for this day, to tick off. */
    val allDayTasks: List<AllDayTask>,
    val prayers: Map<Prayer, LocalTime>,
    val blocks: List<Block>,
    val colours: Map<String, String?>,
    /** Periods he was asleep that touch this day. */
    val asleep: List<AsleepSpan>,
)

data class PlanSummary(
    /** Proposed readiness cut that still needs approval. */
    val loadCut: List<Task> = emptyList(),
    val overflow: List<Task> = emptyList(),
    val unscheduled: List<Task> = emptyList(),
    val catchUpExceeded: Boolean = false,
    /** Tasks whose chosen start time couldn't be used, with why. */
    val pinConflicts: List<PinConflict> = emptyList(),
)

/** One-off feedback for a snackbar; [undoChangeId] adds an Undo action. */
data class PlanMessage(val text: String, val undoChangeId: Long? = null)

sealed interface MoveResult {
    data class Applied(val changeId: Long, val warnings: List<String>) : MoveResult
    data class Rejected(val reasons: List<String>) : MoveResult
}

@Singleton
class PlanRepository @Inject constructor(
    private val blocks: BlockDao,
    private val changeLog: ChangeLogDao,
    private val tasks: TaskRepository,
    private val calendar: CalendarRepository,
    private val settings: SettingsRepository,
    private val readiness: ReadinessRepository,
    private val sleepDao: SleepDao,
    private val dayRules: DayRulesRepository,
    private val plannerSync: PlannerCalendarSync,
    private val allDayTicks: AllDayTicks,
    private val clock: Clock,
) {
    private suspend fun scheduler(): Scheduler = Scheduler(dayRules.current())

    /** Replans can be triggered from several places at once; two running together would double-place tasks. */
    private val replanLock = Mutex()

    private val _summary = MutableStateFlow(PlanSummary())
    val summary: StateFlow<PlanSummary> = _summary

    /** Result of the last Google push, for display; null when fine or not connected. */
    private val _syncProblem = MutableStateFlow<String?>(null)
    val syncProblem: StateFlow<String?> = _syncProblem

    /** Removes a task's blocks, so an edited task (new time or day) is placed afresh by the next replan. */
    suspend fun forgetBlocks(taskId: Long) = blocks.deleteForTask(taskId)

    private data class CalSettings(
        val calendars: List<CalendarInfo>,
        val selected: Set<String>,
        val plannerId: String?,
        val fajr: Map<LocalDate, LocalTime>,
        val asleep: List<AsleepSpan>,
    )

    private val asleepSpans: Flow<List<AsleepSpan>> =
        flow { emitAll(sleepDao.observeSince(sleepLookbackStart().toEpochMilli())) }
            .map { rows -> rows.map { AsleepSpan(Instant.ofEpochMilli(it.startMillis), Instant.ofEpochMilli(it.endMillis)) } }

    private val calSettings: Flow<CalSettings> =
        combine(settings.calendars, settings.selectedCalendarIds, settings.plannerCalendarId, settings.fajrWeeks, asleepSpans) { cals, sel, planner, fajr, asleep ->
            CalSettings(cals, sel ?: FixedEvents.defaultSelection(cals), planner, fajr, asleep)
        }

    fun dayViews(from: LocalDate, to: LocalDate): Flow<List<DayView>> =
        combine(blocks.observeBetween(from.toString(), to.toString()), calendar.cachedEvents, calSettings, allDayTicks.done, tasks.tasks) { rows, events, cs, done, taskList ->
            val all = rows.map { it.toBlock() }
            val commitments = commitmentEvents(all)
            // An all-day event that has become a task is ticked off as a task, with a time of its own.
            val becameTasks = taskList.mapNotNull(CalendarTasks::allDayKey).toSet()
            generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.map { date ->
                val ctx = context(date, events, cs, existing = emptyList(), notBefore = null, commitments = commitments)
                DayView(
                    date = date,
                    fixed = ctx.fixed.filter { it.start.atZone(clock.zone).toLocalDate() <= date && it.end.atZone(clock.zone).toLocalDate() >= date },
                    allDay = FixedEvents.allDayOn(events, cs.selected, cs.plannerId, date),
                    allDayTasks = AllDayTasks.on(events, cs.selected, cs.plannerId, date, done).filter { it.key !in becameTasks },
                    prayers = ctx.prayers,
                    blocks = all.filter { it.date == date && it.kind == BlockKind.TASK },
                    colours = cs.calendars.associate { it.id to it.colorHex },
                    asleep = cs.asleep.filter { it.start.atZone(clock.zone).toLocalDate() <= date && it.end.atZone(clock.zone).toLocalDate() >= date },
                )
            }.toList()
        }

    /**
     * Rebuilds the plan for the next [HORIZON_DAYS] days. Kept: locked blocks, the block in progress, and
     * blocks of finished tasks. Everything else is cleared and placed again, so blocks missed earlier
     * today move forward. Pushes the result to the Planner calendar.
     */
    suspend fun replan(): PlanSummary = replanLock.withLock { buildPlan() }

    /** Sleep data arrives after the fact (Fitbit syncs late); when it has changed since the last plan, plan again. */
    suspend fun replanIfSleepChanged() {
        val latest = sleepDao.since(sleepLookbackStart().toEpochMilli()).maxOfOrNull { it.endMillis }
        if (latest != settings.plannedSleepEnd()) replan()
    }

    private fun sleepLookbackStart(): Instant = clock.instant().minus(Duration.ofDays(SLEEP_LOOKBACK_DAYS))

    private suspend fun buildPlan(): PlanSummary {
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val nowMin = LocalTime.now(clock).let { it.hour * 60 + it.minute }
        materialiseCalendarTasks(today)
        val allTasks = tasks.snapshot()
        val byId = allTasks.associateBy { it.id }

        val kept = mutableListOf<Block>()
        val commitments = mutableListOf<Block>()
        val clear = mutableListOf<Long>()
        for (row in blocks.fromDate(today.toString())) {
            val b = row.toBlock()
            if (Commitments.isCommitment(b)) {
                commitments += b // a commitment is never cleared or moved by a replan
                continue
            }
            val task = b.taskId?.let(byId::get)
            val inProgress = b.date == today && b.start.toMinuteOfDay() <= nowMin && b.end.toMinuteOfDay() > nowMin
            val keep = task != null && task.status != TaskStatus.DROPPED &&
                (b.locked || inProgress || task.status == TaskStatus.DONE)
            if (keep) kept += b else clear += b.id
        }

        val events = calendar.cachedEvents.first()
        val cs = calSettingsNow()
        val earliestToday = ((nowMin + 14) / 15 * 15).coerceAtMost(24 * 60 - 1)
        val contexts = (0 until HORIZON_DAYS).map { i ->
            val date = today.plusDays(i.toLong())
            context(
                date, events, cs,
                existing = kept.filter { it.date == date },
                notBefore = if (i == 0) LocalTime.of(earliestToday / 60, earliestToday % 60) else null,
                commitments = commitmentEvents(commitments),
            )
        }

        val band = readiness.finalBandToday() ?: ReadinessBand.GREEN
        val approved = settings.loadCutApprovedOn.first() == today
        val plannable = allTasks.filter { it.status == TaskStatus.OPEN || it.status == TaskStatus.PARTIAL }
        val week = scheduler().planWeek(contexts, plannable, now, band, applyLoadCut = approved)

        if (clear.isNotEmpty()) blocks.delete(clear)
        blocks.upsertAll(week.blocks.map { it.toEntity() })

        val summary = PlanSummary(
            loadCut = if (approved) emptyList() else week.loadCut,
            overflow = week.overflow,
            unscheduled = week.unscheduled,
            catchUpExceeded = week.catchUpExceeded,
            pinConflicts = week.pinConflicts,
        )
        _summary.value = summary
        settings.savePlannedSleepEnd(sleepDao.since(sleepLookbackStart().toEpochMilli()).maxOfOrNull { it.endMillis })
        pushToGoogle()
        return summary
    }

    /**
     * Turns the all-day calendar events of the next days into tasks, which the plan then places at a time. Returns
     * how many tasks it added or let go, so a caller knows whether the plan needs rebuilding.
     */
    suspend fun syncCalendarTasks(): Int = replanLock.withLock { materialiseCalendarTasks(LocalDate.now(clock)) }

    private suspend fun materialiseCalendarTasks(today: LocalDate): Int {
        val cs = calSettingsNow()
        val result = CalendarTasks.sync(
            events = calendar.cachedEvents.first(),
            selected = cs.selected,
            plannerCalendarId = cs.plannerId,
            from = today,
            days = HORIZON_DAYS,
            zone = clock.zone,
            existing = tasks.snapshot(),
            doneKeys = allDayTicks.done.first(),
        )
        tasks.addNew(result.create)
        tasks.saveAll(result.drop)
        return result.create.size + result.drop.size
    }

    /** The first block a task has from today on, if the plan placed it. */
    suspend fun placementOf(taskId: Long): Block? =
        blocks.fromDate(LocalDate.now(clock).toString()).map { it.toBlock() }.firstOrNull { it.taskId == taskId }

    /** Where a new task could go on [date] right now: free stretches inside the work window, away from everything else. */
    suspend fun freeWindows(date: LocalDate): List<FreeWindow> {
        val today = LocalDate.now(clock)
        if (date < today) return emptyList()
        val state = stateFor(listOf(date))
        val nowMin = LocalTime.now(clock).let { it.hour * 60 + it.minute }
        val earliest = ((nowMin + 14) / 15 * 15).coerceAtMost(24 * 60 - 1)
        val ctx = state.contexts.getValue(date).copy(
            existing = state.blocks.filter { it.date == date && it.kind == BlockKind.TASK },
            notBefore = if (date == today) LocalTime.of(earliest / 60, earliest % 60) else null,
        )
        return scheduler().freeWindows(ctx)
    }

    suspend fun approveLoadCut(): PlanSummary {
        settings.approveLoadCut(LocalDate.now(clock))
        return replan()
    }

    /** A drag or resize. Validated against fixed events, prayers, sleep, the work window and other blocks. */
    suspend fun move(blockId: Long, date: LocalDate, startMin: Int, endMin: Int, actor: Actor = Actor.USER): MoveResult {
        val row = blocks.get(blockId) ?: return MoveResult.Rejected(listOf("That block no longer exists"))
        if (startMin !in 0 until 24 * 60 || endMin !in 1 until 24 * 60 || endMin <= startMin) {
            return MoveResult.Rejected(listOf("That time isn't valid"))
        }
        val title = row.taskId?.let { id -> tasks.snapshot().firstOrNull { it.id == id }?.title } ?: row.label ?: "block"
        val op = Op.Move(blockId, date, startMin.toLocalTime(), endMin.toLocalTime())
        return applyChanges(ChangeSet(listOf(op), actor), "Moved $title")
    }

    /** Checks a change against the rules without applying it. */
    suspend fun check(changeSet: ChangeSet): Validation =
        scheduler().validate(changeSet, stateFor(affectedDates(changeSet)))

    /** Validates a change and, if it is allowed, applies it as one logged change that can be undone. */
    suspend fun applyChanges(changeSet: ChangeSet, summary: String): MoveResult {
        val state = stateFor(affectedDates(changeSet))
        val verdict = scheduler().validate(changeSet, state)
        if (!verdict.ok) {
            return MoveResult.Rejected(verdict.violations.filter { it.hard }.map { it.message }.distinct())
        }
        val applied = apply(state.blocks, changeSet)
        for (diff in applied.diffs) {
            val after = diff.after
            val before = diff.before
            when {
                after != null -> {
                    val current = blocks.get(after.id)
                    blocks.upsert(after.toEntity(current?.syncedStartMillis, current?.syncedEndMillis))
                }
                before != null -> blocks.delete(listOf(before.id))
            }
        }
        val id = log(changeSet.actor, summary, applied.diffs)
        pushToGoogle()
        return MoveResult.Applied(id, verdict.violations.filterNot { it.hard }.map { it.message }.distinct())
    }

    /**
     * Adds a commitment such as an interview: a locked fixed block that is mirrored to the Planner calendar.
     * Tasks are replanned around it. Undoing removes it.
     */
    suspend fun addCommitment(title: String, date: LocalDate, start: LocalTime, end: LocalTime, actor: Actor): MoveResult {
        if (title.isBlank() || end <= start) {
            return MoveResult.Rejected(listOf("A commitment needs a title and an end time after its start"))
        }
        val block = Block(
            date = date, start = start, end = end, kind = BlockKind.FIXED, locked = true,
            origin = if (actor == Actor.AI) BlockOrigin.AI else BlockOrigin.USER, label = title.trim(),
        )
        val saved = block.copy(id = blocks.upsert(block.toEntity()))
        val changeId = log(actor, "Added ${saved.label}", listOf(BlockDiff(null, saved)))
        replan()
        return MoveResult.Applied(changeId, emptyList())
    }

    private suspend fun affectedDates(changeSet: ChangeSet): List<LocalDate> {
        val dates = changeSet.ops.flatMap { op ->
            when (op) {
                is Op.Move -> listOfNotNull(op.date, blocks.get(op.blockId)?.date?.let(LocalDate::parse))
                is Op.Drop -> listOfNotNull(blocks.get(op.blockId)?.date?.let(LocalDate::parse))
            }
        }
        return dates.ifEmpty { listOf(LocalDate.now(clock)) }
    }

    /** Takes the block off the plan. The task stays open and is placed again by the next replan. */
    suspend fun unschedule(blockId: Long): Long? {
        val row = blocks.get(blockId) ?: return null
        val applied = apply(listOf(row.toBlock()), ChangeSet(listOf(Op.Drop(blockId)), Actor.USER))
        blocks.delete(listOf(blockId))
        val title = row.taskId?.let { id -> tasks.snapshot().firstOrNull { it.id == id }?.title } ?: "block"
        val id = log(Actor.USER, "Removed $title from the plan", applied.diffs)
        pushToGoogle()
        return id
    }

    suspend fun setLocked(blockId: Long, locked: Boolean) {
        val row = blocks.get(blockId) ?: return
        blocks.upsert(row.copy(locked = locked, origin = if (locked) "USER" else row.origin))
    }

    suspend fun undo(changeId: Long): Boolean {
        val entry = changeLog.get(changeId)?.takeIf { !it.undone } ?: return false
        for (d in decodeDiffs(entry.diffJson).asReversed()) {
            val before = d.before
            val after = d.after
            when {
                before != null -> {
                    val current = blocks.get(before.id)
                    blocks.upsert(before.toEntity(current?.syncedStartMillis, current?.syncedEndMillis))
                }
                after != null -> blocks.delete(listOf(after.id))
            }
        }
        changeLog.markUndone(changeId)
        if (decodeDiffs(entry.diffJson).any { d -> listOfNotNull(d.before, d.after).any(Commitments::isCommitment) }) replan() else pushToGoogle()
        return true
    }

    suspend fun pushToGoogle() {
        _syncProblem.value = when (val r = plannerSync.sync()) {
            PlannerSyncResult.Done, PlannerSyncResult.NotConnected -> null
            is PlannerSyncResult.Failed -> r.message
        }
    }

    private suspend fun calSettingsNow(): CalSettings = calSettings.first()

    private suspend fun stateFor(dates: List<LocalDate>): PlanState {
        val events = calendar.cachedEvents.first()
        val cs = calSettingsNow()
        val from = dates.min()
        val to = dates.max()
        val all = blocks.between(from.toString(), to.toString()).map { it.toBlock() }
        val commitments = commitmentEvents(all)
        val contexts = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }
            .associateWith { context(it, events, cs, emptyList(), null, commitments) }
        return PlanState(all, contexts, tasks.snapshot().associateBy { it.id })
    }

    private fun commitmentEvents(all: List<Block>): List<FixedEvent> =
        all.filter(Commitments::isCommitment).map { Commitments.toFixedEvent(it, clock.zone) }

    private fun context(
        date: LocalDate,
        events: List<CalendarEvent>,
        cs: CalSettings,
        existing: List<Block>,
        notBefore: LocalTime?,
        commitments: List<FixedEvent> = emptyList(),
    ): DayContext {
        val prayerCalendar = cs.calendars.firstOrNull { PrayerTimes.isPrayerCalendar(it.name) }?.id
        val byDate = prayerCalendar?.let { PrayerTimes.fromEvents(events, it, clock.zone) }.orEmpty()
        return DayContext(
            date = date,
            zone = clock.zone,
            fixed = FixedEvents.from(events, cs.selected, cs.plannerId) + commitments,
            prayers = PrayerTimes.forDate(date, byDate, cs.fajr[Weeks.mondayOf(date)]),
            existing = existing,
            notBefore = notBefore,
            asleep = cs.asleep,
        )
    }

    private suspend fun log(actor: Actor, summary: String, diffs: List<BlockDiff>): Long =
        changeLog.insert(
            ChangeLogEntity(
                ts = clock.millis(),
                actor = actor.name,
                summary = summary,
                diffJson = encodeDiffs(diffs),
                undone = false,
            ),
        )

    private fun LocalTime.toMinuteOfDay() = hour * 60 + minute

    private fun Int.toLocalTime(): LocalTime = LocalTime.of(this / 60, this % 60)

    companion object {
        const val HORIZON_DAYS = 7
        private const val SLEEP_LOOKBACK_DAYS = 21L
    }
}
