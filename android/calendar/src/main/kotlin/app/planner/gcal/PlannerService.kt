package app.planner.gcal

import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.SyncStamp
import app.planner.engine.model.TaskStatus
import app.planner.engine.ports.ChangeDraft
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.ExecutionLogStore
import app.planner.engine.ports.ImportReport
import app.planner.engine.ports.NowProvider
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.ProposalOutcome
import app.planner.engine.ports.ProposalPort
import app.planner.engine.ports.ReplanReason
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.SnapshotStore
import app.planner.engine.ports.TaskStore
import app.planner.engine.ports.TodayState
import app.planner.engine.ports.WeekState
import app.planner.engine.schedule.BlockPlacer
import app.planner.engine.schedule.PlanningEngine
import app.planner.engine.schedule.PrayerModel
import app.planner.gcal.plan.PlanningContext
import app.planner.gcal.plan.Proposer
import app.planner.gcal.plan.TemplateImporter
import app.planner.gcal.read.ScreenStates
import app.planner.gcal.sync.CalendarSync
import app.planner.gcal.sync.UserEdits
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The planner over the engine and the stores: [PlannerQueries] for the screens and the assistant's read tools,
 * [ProposalPort] for every request that would change the calendar. Proposals are saved to [ChangeSetStore] and stop
 * there; only CalendarWriter writes, and only with an ApprovedChangeSet.
 */
class PlannerService(
    private val engine: PlanningEngine,
    private val prayers: PrayerModel,
    private val placer: BlockPlacer,
    private val sync: CalendarSync,
    private val tasks: TaskStore,
    private val changeSets: ChangeSetStore,
    private val snapshots: SnapshotStore,
    private val settings: SettingsStore,
    private val logs: ExecutionLogStore,
    private val auth: AuthTracker,
    private val now: NowProvider,
    private val clock: () -> Instant,
    private val ids: IdSource = IdSource.Random,
    /** Looks up one event in Google when it is outside the synced window. */
    private val fetch: suspend (app.planner.engine.model.EventRef) -> EventSnapshot? = { null },
    /** How often the Today screen re-evaluates "now". */
    private val tickMillis: Long = 60_000,
) : PlannerQueries, ProposalPort {
    private val importer = TemplateImporter(tasks)

    private val proposer = Proposer(
        loadContext = { from, to -> context(from, to) },
        engine = engine, placer = placer, tasks = tasks, changeSets = changeSets, now = now, clock = clock, ids = ids, fetch = fetch,
    )

    init {
        sync.onUserEdits = ::handleUserEdits
    }

    private suspend fun context(from: LocalDate, toExclusive: LocalDate): PlanningContext =
        PlanningContext(CalendarDirectory(sync.calendarList()), snapshots.events(from, toExclusive), tasks.all(), settings.current(), prayers)

    // ---------------------------------------------------------------- PlannerQueries

    private val ticker: Flow<LocalDateTime> = flow {
        while (true) {
            emit(now.now())
            delay(tickMillis)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeToday(): Flow<TodayState> =
        ticker.map { it.toLocalDate() }.distinctUntilChanged().flatMapLatest { date ->
            val inputs = combine(
                snapshots.observe(date.minusDays(7), date.plusDays(8)),
                tasks.observe(),
                logs.observeDay(date),
                settings.observe(),
                sync.calendars,
            ) { events, taskList, dayLogs, s, cals -> Inputs(events, taskList, dayLogs, s, cals) }
            combine(inputs, auth.state, snapshots.observeSync(), ticker) { inp, authState, stamp, at ->
                val ctx = PlanningContext(CalendarDirectory(inp.calendars), inp.events, inp.tasks, inp.settings, prayers)
                val blocks = ScreenStates.blocks(ctx, date, inp.logs)
                val nowIndex = blocks.indexOfFirst { !at.isBefore(it.start) && at.isBefore(it.end) && !it.isFixed }
                    .takeIf { it >= 0 }
                    ?: blocks.indexOfFirst { !at.isBefore(it.start) && at.isBefore(it.end) }.takeIf { it >= 0 }
                TodayState(
                    date = date,
                    blocks = blocks,
                    nowIndex = nowIndex,
                    unscheduled = ScreenStates.unscheduled(inp.tasks, date),
                    flags = ScreenStates.weekDay(ctx, date, inp.tasks, inp.logs).flags,
                    anchors = ctx.anchors(listOf(date)),
                    auth = authState,
                    lastSync = stamp,
                )
            }
        }

    private class Inputs(
        val events: List<EventSnapshot>,
        val tasks: List<app.planner.engine.model.Task>,
        val logs: List<app.planner.engine.model.ExecutionLog>,
        val settings: app.planner.engine.model.Settings,
        val calendars: List<app.planner.engine.ports.CalendarInfo>,
    )

    override fun observeWeek(start: LocalDate): Flow<WeekState> = combine(
        snapshots.observe(start.minusDays(7), start.plusDays(14)),
        tasks.observe(),
        settings.observe(),
        sync.calendars,
    ) { events, taskList, s, cals ->
        val ctx = PlanningContext(CalendarDirectory(cals), events, taskList, s, prayers)
        WeekState(start, (0L..6L).map { ScreenStates.weekDay(ctx, start.plusDays(it), taskList) })
    }

    override suspend fun week(start: LocalDate): WeekState {
        val ctx = context(start.minusDays(7), start.plusDays(14))
        return WeekState(start, (0L..6L).map { ScreenStates.weekDay(ctx, start.plusDays(it), ctx.tasks) })
    }

    override suspend fun anchors(date: LocalDate): List<PrayerAnchor> = context(date.minusDays(7), date.plusDays(8)).anchors(listOf(date))

    override suspend fun findEvents(query: String, from: LocalDate, toExclusive: LocalDate): List<EventSnapshot> {
        val ctx = context(from, toExclusive)
        val words = query.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return ctx.events
            .filter { e -> !ctx.isPrayer(e) && words.all { e.title.lowercase().contains(it) } && starts(e) in from..toExclusive.minusDays(1) }
            .sortedWith(compareBy({ startTime(it) }, { it.title }))
    }

    private fun starts(e: EventSnapshot): LocalDate = startTime(e).toLocalDate()

    private fun startTime(e: EventSnapshot): LocalDateTime = when (val t = e.time) {
        is EventTime.Timed -> t.start
        is EventTime.AllDay -> t.start.atStartOfDay()
    }

    override suspend fun refresh(): SyncStamp = sync.refresh()

    // ---------------------------------------------------------------- ProposalPort

    override suspend fun propose(drafts: List<ChangeDraft>, source: ChangeSource, summary: String): ProposalOutcome =
        proposer.propose(drafts, source, summary)

    override suspend fun replan(reason: ReplanReason, source: ChangeSource): ProposalOutcome = proposer.replan(reason, source)

    override suspend fun planDays(days: List<LocalDate>, source: ChangeSource): ProposalOutcome {
        if (days.isNotEmpty()) importTemplate(days.min(), days.max().plusDays(1))
        return proposer.planDays(days, source)
    }

    override suspend fun rollover(targetWeekStart: LocalDate, source: ChangeSource): ProposalOutcome =
        proposer.rollover(targetWeekStart, source) { from, to -> importTemplate(from, to) }

    override suspend fun importTemplate(from: LocalDate, toExclusive: LocalDate): ImportReport =
        importer.import(context(from.minusDays(1), toExclusive.plusDays(1)), from, toExclusive)

    // ---------------------------------------------------------------- the user's own edits

    /**
     * newplan 8: the user's edit in Google Calendar is final. Deleted blocks unlink from their task (back to the
     * backlog when none is left); moved blocks that now break a constraint or overlap something get a replan proposal.
     */
    private suspend fun handleUserEdits(edits: UserEdits) {
        if (edits.deleted.isNotEmpty()) {
            val gone = edits.deleted.map { it.ref }.toSet()
            val changed = tasks.all().filter { t -> t.calendarEventIds.any { it in gone } }.map { t ->
                val left = t.calendarEventIds.filter { it !in gone }
                t.copy(calendarEventIds = left, status = if (left.isEmpty() && t.status == TaskStatus.SCHEDULED) TaskStatus.BACKLOG else t.status)
            }
            if (changed.isNotEmpty()) tasks.upsert(changed)
        }
        val moved = edits.moved.map { it.second }
        if (moved.isEmpty()) return
        // A proposal is saved for the approval card; NothingToDo needs nothing.
        proposer.replan(ReplanReason.UserEdited(moved.map { it.ref }), ChangeSource.REPLAN)
    }
}
