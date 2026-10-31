package app.planner.engine.fakes

import app.planner.engine.changeset.Change
import app.planner.engine.changeset.ChangeFlag
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.PlannerTags
import app.planner.engine.model.EventRef
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.SyncStamp
import app.planner.engine.model.Tier
import app.planner.engine.ports.AuthState
import app.planner.engine.ports.ChangeDraft
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.ImportReport
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.ProposalOutcome
import app.planner.engine.ports.ProposalPort
import app.planner.engine.ports.ReplanReason
import app.planner.engine.ports.TodayBlock
import app.planner.engine.ports.TodayState
import app.planner.engine.ports.WeekDay
import app.planner.engine.ports.WeekState
import app.planner.engine.schedule.DayFlag
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Read side over [ReferenceWeek], shifted so its Monday is [weekStart]. For UI previews and demo mode. */
class FakePlannerQueries(
    private val now: () -> LocalDateTime,
    private val weekStart: LocalDate = ReferenceWeek.monday,
    val auth: MutableStateFlow<AuthState> = MutableStateFlow(AuthState.SIGNED_IN),
) : PlannerQueries {
    private val shiftDays = java.time.temporal.ChronoUnit.DAYS.between(ReferenceWeek.monday, weekStart)
    private val tasksById = ReferenceWeek.tasks.associateBy { it.id }

    private fun blocksOn(date: LocalDate): List<TodayBlock> {
        val ref = date.minusDays(shiftDays)
        val tasks = ReferenceWeek.referencePlacements.filter { it.start.toLocalDate() == ref }.map { p ->
            val task = tasksById.getValue(p.taskId)
            TodayBlock(
                ref = EventRef(ReferenceWeek.calendarId(task.category), "${p.taskId}-${p.chunkIndex}"),
                taskId = p.taskId,
                title = task.title,
                calendarName = task.category.calendarName,
                category = task.category,
                start = p.start.plusDays(shiftDays),
                end = p.end.plusDays(shiftDays),
                tier = p.tier,
                prayerBufferMin = p.prayerBufferMin,
                coveredPrayers = p.coveredPrayers,
                isFixed = false,
                chunkLabel = p.label,
            )
        }
        val fixed = ReferenceWeek.fixed.filter { it.start.toLocalDate() == ref }.map { f ->
            TodayBlock(
                ref = f.ref, taskId = null, title = f.title, calendarName = "Personal", category = null,
                start = f.start.plusDays(shiftDays), end = f.end.plusDays(shiftDays), tier = Tier.NORMAL,
                prayerBufferMin = 0, coveredPrayers = emptyList(), isFixed = true,
            )
        }
        return (tasks + fixed).sortedBy { it.start }
    }

    private fun day(date: LocalDate): WeekDay {
        val blocks = blocksOn(date)
        val maxTier = blocks.maxOfOrNull { it.tier } ?: Tier.NORMAL
        val flags = buildSet {
            if (maxTier >= Tier.REST) add(DayFlag.USES_REST_TIME)
            if (maxTier >= Tier.EXTENDED) add(DayFlag.EXTENDED_DAY)
            if (maxTier >= Tier.OVER_SOFT_LIMIT) add(DayFlag.OVER_SOFT_LIMIT)
            if (date.dayOfWeek.value >= 6) add(DayFlag.INTERPOLATED_PRAYERS)
        }
        return WeekDay(date, blocks, blocks.maxOfOrNull { it.end }?.toLocalTime(), maxTier, flags, overflowCount = 0)
    }

    private fun today(): TodayState {
        val n = now()
        val d = day(n.toLocalDate())
        val nowIndex = d.blocks.indexOfFirst { !n.isBefore(it.start) && n.isBefore(it.end) }.takeIf { it >= 0 }
        return TodayState(
            date = d.date, blocks = d.blocks, nowIndex = nowIndex, unscheduled = emptyList(), flags = d.flags,
            anchors = anchorsFor(d.date), auth = auth.value, lastSync = SyncStamp(Instant.EPOCH, ok = true),
        )
    }

    private fun anchorsFor(date: LocalDate): List<PrayerAnchor> =
        ReferenceWeek.anchorsOn(date.minusDays(shiftDays)).map { it.copy(date = date) }

    override fun observeToday(): Flow<TodayState> = combine(auth, flowOf(Unit)) { _, _ -> today() }
    override fun observeWeek(start: LocalDate): Flow<WeekState> = flowOf(WeekState(start, (0L..6L).map { day(start.plusDays(it)) }))
    override suspend fun week(start: LocalDate): WeekState = WeekState(start, (0L..6L).map { day(start.plusDays(it)) })
    override suspend fun anchors(date: LocalDate): List<PrayerAnchor> = anchorsFor(date)
    override suspend fun findEvents(query: String, from: LocalDate, toExclusive: LocalDate): List<EventSnapshot> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it < toExclusive }
            .flatMap { blocksOn(it) }
            .filter { it.title.contains(query, ignoreCase = true) && it.ref != null }
            .map { it.toSnapshot() }
            .toList()
    override suspend fun refresh(): SyncStamp = SyncStamp(Instant.now(), ok = true)
}

fun TodayBlock.toSnapshot(): EventSnapshot = EventSnapshot(
    ref = ref!!,
    calendarName = calendarName,
    title = listOfNotNull(title, chunkLabel).joinToString(" "),
    time = EventTime.Timed(start, end),
    etag = "\"1\"",
    updated = Instant.EPOCH,
    privateProps = if (taskId != null) mapOf(PlannerTags.PLANNER to "1", PlannerTags.TASK_ID to taskId) else emptyMap(),
)

/**
 * Returns a canned "Halaqa ran over: move 2 blocks" ChangeSet for every request and saves it to [store],
 * so approval cards can be built and tested without the engine.
 */
class FakeProposalPort(
    private val store: ChangeSetStore,
    private val clock: () -> Instant = { Instant.now() },
) : ProposalPort {
    val calls: MutableList<Any> = mutableListOf()

    fun sample(source: ChangeSource, summary: String): ChangeSet {
        val wed = ReferenceWeek.days[2]
        val cal = ReferenceWeek.calendarId(app.planner.engine.model.CalendarCategory.ACADEMIC)
        fun snap(id: String, title: String, from: String, to: String) = EventSnapshot(
            ref = EventRef(cal, id), calendarName = "Academic", title = title,
            time = EventTime.Timed(wed.atTime(java.time.LocalTime.parse(from)), wed.atTime(java.time.LocalTime.parse(to))),
            etag = "\"1\"", updated = Instant.EPOCH, privateProps = mapOf(PlannerTags.PLANNER to "1"),
        )
        fun draft(s: EventSnapshot, from: String, to: String) = EventDraft(
            s.ref.calendarId, s.title,
            EventTime.Timed(wed.atTime(java.time.LocalTime.parse(from)), wed.atTime(java.time.LocalTime.parse(to))),
            privateProps = s.privateProps,
        )
        val a = snap("ps-cw-wed-2", "P&S CW (2/2)", "16:46", "17:31")
        val b = snap("os-ws-wed-2", "OS worksheet (2/2)", "17:31", "18:31")
        return ChangeSet(
            id = "cs-" + clock().toEpochMilli(),
            source = source,
            summary = summary,
            changes = listOf(
                Change("c1", ChangeType.MOVE, a, draft(a, "17:46", "18:46"), flags = setOf(ChangeFlag.EXTENDED_DAY), note = "Includes 15 min for Isha"),
                Change("c2", ChangeType.MOVE, b, draft(b, "18:46", "19:46"), flags = setOf(ChangeFlag.EXTENDED_DAY)),
            ),
            createdAt = clock(),
        )
    }

    private suspend fun propose(source: ChangeSource, summary: String): ProposalOutcome {
        val cs = sample(source, summary)
        store.save(cs)
        return ProposalOutcome.Proposed(cs, emptyList())
    }

    override suspend fun propose(drafts: List<ChangeDraft>, source: ChangeSource, summary: String): ProposalOutcome {
        calls.add(drafts)
        return propose(source, summary)
    }

    override suspend fun replan(reason: ReplanReason, source: ChangeSource): ProposalOutcome {
        calls.add(reason)
        return propose(source, "Replan: move 2 blocks")
    }

    override suspend fun planDays(days: List<LocalDate>, source: ChangeSource): ProposalOutcome {
        calls.add(days)
        return propose(source, "Plan ${days.size} day(s)")
    }

    override suspend fun rollover(targetWeekStart: LocalDate, source: ChangeSource): ProposalOutcome {
        calls.add(targetWeekStart)
        return propose(source, "Copy template into week of $targetWeekStart")
    }

    override suspend fun importTemplate(from: LocalDate, toExclusive: LocalDate): ImportReport = ImportReport(0, 0, 0)
}
