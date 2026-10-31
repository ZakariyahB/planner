package app.planner.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.ProposalPort
import app.planner.engine.ports.SettingsStore
import app.planner.ui.model.Outcomes
import app.planner.ui.model.WeekRhythm
import app.planner.ui.model.WeekRhythmUi
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface WeekEvent {
    data class OpenApproval(val changeSetId: String) : WeekEvent
    data class Message(val text: String) : WeekEvent
}

data class WeekScreenUi(val week: WeekRhythmUi, val isThisWeek: Boolean, val busy: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WeekViewModel @Inject constructor(
    private val queries: PlannerQueries,
    private val proposals: ProposalPort,
    settingsStore: SettingsStore,
) : ViewModel() {
    private val events = Channel<WeekEvent>(Channel.BUFFERED)
    val messages: Flow<WeekEvent> = events.receiveAsFlow()

    private fun mondayOf(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private val start = MutableStateFlow(mondayOf(LocalDate.now()))
    private val busy = MutableStateFlow(false)

    private val week = start.flatMapLatest { s -> queries.observeWeek(s) }.mapLatest { w ->
        val anchors = w.days.flatMap { d -> runCatching { queries.anchors(d.date) }.getOrDefault(emptyList()) }
        w to anchors
    }

    val state: StateFlow<WeekScreenUi?> = combine(week, settingsStore.observe(), busy) { (w, anchors), settings, b ->
        val today = LocalDate.now()
        WeekScreenUi(WeekRhythm.build(w, anchors, settings, today), isThisWeek = w.start == mondayOf(today), busy = b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun previous() { start.value = start.value.minusWeeks(1) }

    fun next() { start.value = start.value.plusWeeks(1) }

    fun thisWeek() { start.value = mondayOf(LocalDate.now()) }

    /** "Copy schedule template into next week": the engine re-times it; the result waits for approval. */
    fun copyTemplateIntoNextWeek() = viewModelScope.launch {
        val target = start.value.plusWeeks(1)
        busy.value = true
        val result = runCatching { proposals.rollover(target, ChangeSource.USER) }
        busy.value = false
        result.onFailure { events.send(WeekEvent.Message(it.message ?: "Could not copy the template")) }
        result.onSuccess { o ->
            val ui = Outcomes.describe(o)
            ui.openChangeSetId?.let { events.send(WeekEvent.OpenApproval(it)) }
            ui.message?.let { events.send(WeekEvent.Message(it)) }
        }
    }
}
