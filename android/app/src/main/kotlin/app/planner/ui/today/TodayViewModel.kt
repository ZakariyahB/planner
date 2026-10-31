package app.planner.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.Settings
import app.planner.engine.model.SyncStamp
import app.planner.engine.model.Task
import app.planner.engine.ports.AuthState
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.ExecutionLogStore
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.ProposalPort
import app.planner.engine.ports.ReplanReason
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.TodayBlock
import app.planner.engine.schedule.DayFlag
import app.planner.ui.model.DayTimeline
import app.planner.ui.model.DayTimelines
import app.planner.ui.model.ExecutionLogs
import app.planner.ui.model.Fmt
import app.planner.ui.model.Outcomes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UnscheduledUi(val task: Task, val title: String, val detail: String)

data class TodayUi(
    val timeline: DayTimeline,
    val auth: AuthState,
    /** "Synced 10:42", "Last sync failed: ...", or null before the first sync. */
    val syncLine: String?,
    val syncFailed: Boolean,
    val unscheduled: List<UnscheduledUi>,
    val pendingCount: Int,
    val dayFlags: List<String>,
    val settings: Settings,
    /** Asks "How focused were you?" for this log after Done. */
    val focusFor: ExecutionLog?,
    val busy: Boolean,
)

sealed interface TodayEvent {
    data class OpenApproval(val changeSetId: String) : TodayEvent
    data class Message(val text: String) : TodayEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val queries: PlannerQueries,
    private val logs: ExecutionLogStore,
    private val proposals: ProposalPort,
    changeSets: ChangeSetStore,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val events = Channel<TodayEvent>(Channel.BUFFERED)
    val messages: Flow<TodayEvent> = events.receiveAsFlow()

    private val focusFor = MutableStateFlow<ExecutionLog?>(null)
    private val busy = MutableStateFlow(false)

    /** Ticks every 30 s so the now card and the heading move on their own. */
    private val clock: Flow<LocalDateTime> = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }

    private val todayWithLogs = queries.observeToday().flatMapLatest { t ->
        logs.observeDay(t.date).map { list -> t to list.associateBy { it.id } }.catch { emit(t to emptyMap()) }
    }

    private val extras = combine(changeSets.observePending(), focusFor, busy) { pending, focus, b ->
        Triple(pending.sumOf { cs -> cs.changes.size.coerceAtLeast(1) }, focus, b)
    }

    val state: StateFlow<TodayUi?> = combine(todayWithLogs, clock, settingsStore.observe(), extras) { (today, logMap), now, settings, (pending, focus, b) ->
        val timeline = DayTimelines.build(today.date, today.blocks, today.anchors, now, settings.normalEnd, settings.prayerSlotMin, logMap)
        TodayUi(
            timeline = timeline,
            auth = today.auth,
            syncLine = today.lastSync?.let { syncLine(it, settings) },
            syncFailed = today.lastSync?.ok == false,
            unscheduled = today.unscheduled.map { t ->
                UnscheduledUi(t, t.title, listOfNotNull(Fmt.duration(t.durationMin), t.category.calendarName, t.earliestStart?.let { "from ${Fmt.time(it)}" }, t.afterPrayer?.let { "after ${Fmt.prayer(it)}" }).joinToString(" · "))
            },
            pendingCount = pending,
            dayFlags = dayFlags(today.flags),
            settings = settings,
            focusFor = focus,
            busy = b,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun syncLine(s: SyncStamp, settings: Settings): String? {
        if (s.at == Instant.EPOCH) return null
        val at = s.at.atZone(settings.zone).toLocalDateTime()
        val ago = Duration.between(s.at, Instant.now())
        val whenText = if (ago.toHours() < 20) Fmt.time(at) else Fmt.shortDate(at.toLocalDate()) + " " + Fmt.time(at)
        return if (s.ok) "Synced with Google $whenText" else "Last sync failed $whenText" + (s.message?.let { ": $it" } ?: "")
    }

    private fun dayFlags(flags: Set<DayFlag>): List<String> = buildList {
        when {
            DayFlag.OVER_SOFT_LIMIT in flags -> add("Over the soft limit today")
            DayFlag.EXTENDED_DAY in flags -> add("Extended day")
            DayFlag.USES_REST_TIME in flags -> add("Uses rest time")
        }
        if (DayFlag.INTERPOLATED_PRAYERS in flags) add("Prayer times estimated (~)")
        if (DayFlag.HAS_OVERFLOW in flags) add("Some work goes to the catch-up day")
    }

    private fun logFor(block: TodayBlock): ExecutionLog? = state.value?.timeline?.let { t ->
        (listOfNotNull(t.now) + t.items.filterIsInstance<app.planner.ui.model.TimelineItem.Section>().flatMap { it.stretch.blocks })
            .firstOrNull { it.key == DayTimelines.blockKey(block) }?.log
    }

    fun start(block: TodayBlock) = viewModelScope.launch {
        logs.upsert(ExecutionLogs.start(block, logFor(block), LocalDateTime.now()))
    }

    fun done(block: TodayBlock) = viewModelScope.launch {
        val log = ExecutionLogs.done(block, logFor(block), LocalDateTime.now())
        logs.upsert(log)
        if (settingsStore.current().notifications.askFocusOnDone) focusFor.value = log
    }

    fun focus(rating: Int?) = viewModelScope.launch {
        val log = focusFor.value ?: return@launch
        focusFor.value = null
        if (rating != null) logs.upsert(ExecutionLogs.focus(log, rating))
    }

    fun skip(block: TodayBlock) = viewModelScope.launch {
        logs.upsert(ExecutionLogs.skip(block, logFor(block)))
    }

    /** "Need 30 more": logs it, then asks the engine for a replan. Nothing moves until the change is approved. */
    fun needMore(block: TodayBlock) = viewModelScope.launch {
        val ref = block.ref ?: return@launch events.send(TodayEvent.Message("This block is not on the calendar yet."))
        logs.upsert(ExecutionLogs.extended(block, logFor(block), LocalDateTime.now()))
        val extra = settingsStore.current().notifications.extendMin
        propose { proposals.replan(ReplanReason.Overrun(ref, extra), ChangeSource.USER) }
    }

    /** "Stop here": the rest of the block becomes remaining work, re-planned for approval. */
    fun stopHere(block: TodayBlock) = viewModelScope.launch {
        val now = LocalDateTime.now()
        logs.upsert(ExecutionLogs.stopped(block, logFor(block), now))
        val ref = block.ref ?: return@launch
        propose { proposals.replan(ReplanReason.StoppedEarly(ref, now), ChangeSource.USER) }
    }

    fun planToday() = viewModelScope.launch {
        val date = state.value?.timeline?.date ?: return@launch
        propose { proposals.planDays(listOf(date), ChangeSource.USER) }
    }

    fun refresh() = viewModelScope.launch {
        busy.value = true
        val stamp = runCatching { queries.refresh() }.getOrNull()
        busy.value = false
        if (stamp?.ok == false) events.send(TodayEvent.Message(stamp.message ?: "Could not reach Google Calendar"))
    }

    private suspend fun propose(call: suspend () -> app.planner.engine.ports.ProposalOutcome) {
        busy.value = true
        val outcome = runCatching { call() }
        busy.value = false
        outcome.onFailure { events.send(TodayEvent.Message(it.message ?: "Could not plan that")) }
        outcome.onSuccess { o ->
            val ui = Outcomes.describe(o)
            ui.openChangeSetId?.let { events.send(TodayEvent.OpenApproval(it)) }
            ui.message?.let { events.send(TodayEvent.Message(it)) }
        }
    }
}
