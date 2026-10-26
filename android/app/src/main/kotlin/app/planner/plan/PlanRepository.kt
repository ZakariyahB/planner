package app.planner.plan

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
import app.planner.data.SettingsRepository
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
import app.planner.scheduler.Block
import app.planner.scheduler.BlockDiff
import app.planner.scheduler.ChangeSet
import app.planner.scheduler.DayContext
import app.planner.scheduler.Op
import app.planner.scheduler.PinConflict
import app.planner.scheduler.PlanState
import app.planner.scheduler.Scheduler
import app.planner.scheduler.apply
import app.planner.scheduler.validate
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One day as the timeline draws it. */
data class DayView(
    val date: LocalDate,
    val fixed: List<FixedEvent>,
    val allDay: List<CalendarEvent>,
    val prayers: Map<Prayer, LocalTime>,
    val blocks: List<Block>,
    val colours: Map<String, String?>,
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
    private val plannerSync: PlannerCalendarSync,
    private val clock: Clock,
) {
    val scheduler = Scheduler()

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
    )

    private val calSettings: Flow<CalSettings> =
        combine(settings.calendars, settings.selectedCalendarIds, settings.plannerCalendarId, settings.fajrWeeks) { cals, sel, planner, fajr ->
            CalSettings(cals, sel ?: FixedEvents.defaultSelection(cals), planner, fajr)
        }

    fun dayViews(from: LocalDate, to: LocalDate): Flow<List<DayView>> =
        combine(blocks.observeBetween(from.toString(), to.toString()), calendar.cachedEvents, calSettings) { rows, events, cs ->
            val all = rows.map { it.toBlock() }
            generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.map { date ->
                val ctx = context(date, events, cs, existing = emptyList(), notBefore = null)
                DayView(
                    date = date,
                    fixed = ctx.fixed.filter { it.start.atZone(clock.zone).toLocalDate() <= date && it.end.atZone(clock.zone).toLocalDate() >= date },
                    allDay = FixedEvents.allDayOn(events, cs.selected, cs.plannerId, date),
                    prayers = ctx.prayers,
                    blocks = all.filter { it.date == date },
                    colours = cs.calendars.associate { it.id to it.colorHex },
                )
            }.toList()
        }

    /**
     * Rebuilds the plan for the next [HORIZON_DAYS] days. Kept: locked blocks, the block in progress, and
     * blocks of finished tasks. Everything else is cleared and placed again, so blocks missed earlier
     * today move forward. Pushes the result to the Planner calendar.
     */
    suspend fun replan(): PlanSummary = replanLock.withLock { buildPlan() }

    private suspend fun buildPlan(): PlanSummary {
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val nowMin = LocalTime.now(clock).let { it.hour * 60 + it.minute }
        val allTasks = tasks.snapshot()
        val byId = allTasks.associateBy { it.id }

        val kept = mutableListOf<Block>()
        val clear = mutableListOf<Long>()
        for (row in blocks.fromDate(today.toString())) {
            val b = row.toBlock()
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
            )
        }

        val band = readiness.finalBandToday() ?: ReadinessBand.GREEN
        val approved = settings.loadCutApprovedOn.first() == today
        val plannable = allTasks.filter { it.status == TaskStatus.OPEN || it.status == TaskStatus.PARTIAL }
        val week = scheduler.planWeek(contexts, plannable, now, band, applyLoadCut = approved)

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
        pushToGoogle()
        return summary
    }

    suspend fun approveLoadCut(): PlanSummary {
        settings.approveLoadCut(LocalDate.now(clock))
        return replan()
    }

    /** A drag or resize. Validated against fixed events, prayers, the work window and other blocks. */
    suspend fun move(blockId: Long, date: LocalDate, startMin: Int, endMin: Int, actor: Actor = Actor.USER): MoveResult {
        val row = blocks.get(blockId) ?: return MoveResult.Rejected(listOf("That block no longer exists"))
        if (startMin !in 0 until 24 * 60 || endMin !in 1 until 24 * 60 || endMin <= startMin) {
            return MoveResult.Rejected(listOf("That time isn't valid"))
        }
        val op = Op.Move(blockId, date, startMin.toLocalTime(), endMin.toLocalTime())
        val changeSet = ChangeSet(listOf(op), actor)
        val state = stateFor(listOf(LocalDate.parse(row.date), date))
        val verdict = scheduler.validate(changeSet, state)
        if (!verdict.ok) {
            return MoveResult.Rejected(verdict.violations.filter { it.hard }.map { it.message }.distinct())
        }

        val applied = apply(state.blocks, changeSet)
        applied.diffs.forEach { d -> d.after?.let { blocks.upsert(it.toEntity(row.syncedStartMillis, row.syncedEndMillis)) } }
        val title = row.taskId?.let(state.tasks::get)?.title ?: "block"
        val id = log(actor, "Moved $title", applied.diffs)
        pushToGoogle()
        return MoveResult.Applied(id, verdict.violations.filterNot { it.hard }.map { it.message }.distinct())
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
        pushToGoogle()
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
        val contexts = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }
            .associateWith { context(it, events, cs, emptyList(), null) }
        return PlanState(all, contexts, tasks.snapshot().associateBy { it.id })
    }

    private fun context(date: LocalDate, events: List<CalendarEvent>, cs: CalSettings, existing: List<Block>, notBefore: LocalTime?): DayContext {
        val prayerCalendar = cs.calendars.firstOrNull { PrayerTimes.isPrayerCalendar(it.name) }?.id
        val byDate = prayerCalendar?.let { PrayerTimes.fromEvents(events, it, clock.zone) }.orEmpty()
        return DayContext(
            date = date,
            zone = clock.zone,
            fixed = FixedEvents.from(events, cs.selected, cs.plannerId),
            prayers = PrayerTimes.forDate(date, byDate, cs.fajr[Weeks.mondayOf(date)]),
            existing = existing,
            notBefore = notBefore,
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
    }
}
