package app.planner.ui.backlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.model.Task
import app.planner.engine.model.UnparsedTemplate
import app.planner.engine.ports.ChangeDraft
import app.planner.engine.ports.ProposalPort
import app.planner.engine.ports.TaskStore
import app.planner.ui.model.BacklogItems
import app.planner.ui.model.BacklogUi
import app.planner.ui.model.Outcomes
import app.planner.ui.model.TaskDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface BacklogEvent {
    data class OpenApproval(val changeSetId: String) : BacklogEvent
    data class Message(val text: String) : BacklogEvent
}

data class BacklogScreenUi(val backlog: BacklogUi, val editor: TaskDraft?, val busy: Boolean)

/** Backlog tasks are local (not calendar events), so saving one needs no approval. Planning one does. */
@HiltViewModel
class BacklogViewModel @Inject constructor(
    private val tasks: TaskStore,
    private val proposals: ProposalPort,
) : ViewModel() {
    private val events = Channel<BacklogEvent>(Channel.BUFFERED)
    val messages: Flow<BacklogEvent> = events.receiveAsFlow()

    private val editor = MutableStateFlow<TaskDraft?>(null)
    private val busy = MutableStateFlow(false)
    private val needs = tasks.observeNeedsALook().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<BacklogScreenUi?> = combine(tasks.observe(), needs, editor, busy) { all, n, e, b ->
        BacklogScreenUi(BacklogItems.build(all, n, LocalDate.now()), e, b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add() { editor.value = TaskDraft() }

    fun edit(task: Task) { editor.value = BacklogItems.draftOf(task) }

    fun fix(item: UnparsedTemplate) { editor.value = BacklogItems.draftOf(item) }

    fun update(draft: TaskDraft) { editor.value = draft }

    fun dismissEditor() { editor.value = null }

    fun save() = viewModelScope.launch {
        val d = editor.value ?: return@launch
        if (!d.canSave) return@launch
        val existing = d.id?.let { tasks.get(it) }
        tasks.upsert(listOf(BacklogItems.toTask(d, existing)))
        d.fromTemplate?.let { fixed -> tasks.setNeedsALook(needs.value.filter { it.ref != fixed.ref }) }
        editor.value = null
        events.send(BacklogEvent.Message(if (existing == null) "Added to the backlog" else "Saved"))
    }

    fun delete() = viewModelScope.launch {
        val id = editor.value?.id ?: return@launch
        tasks.delete(id)
        editor.value = null
        events.send(BacklogEvent.Message("Removed from the backlog"))
    }

    /** Asks the engine to place this task; the result waits on the approval card. */
    fun plan(task: Task) = viewModelScope.launch {
        busy.value = true
        val r = runCatching { proposals.propose(listOf(ChangeDraft.ScheduleTask(task.id, task.assignedDay)), ChangeSource.USER, "Plan ${task.title}") }
        busy.value = false
        r.onFailure { events.send(BacklogEvent.Message(it.message ?: "Could not plan that")) }
        r.onSuccess { o ->
            val ui = Outcomes.describe(o)
            ui.openChangeSetId?.let { events.send(BacklogEvent.OpenApproval(it)) }
            ui.message?.let { events.send(BacklogEvent.Message(it)) }
        }
    }

    /** Re-reads this week's and next week's all-day template events into tasks. */
    fun importTemplate() = viewModelScope.launch {
        busy.value = true
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val r = runCatching { proposals.importTemplate(monday, monday.plusWeeks(2)) }
        busy.value = false
        events.send(BacklogEvent.Message(r.fold(Outcomes::importMessage) { it.message ?: "Could not read the calendar" }))
    }
}
