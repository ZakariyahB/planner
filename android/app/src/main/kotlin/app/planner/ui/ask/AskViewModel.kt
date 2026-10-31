package app.planner.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.ports.AssistantPort
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.ChatRole
import app.planner.engine.ports.ChatTurn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A proposal shown inline under the assistant's message. */
data class InlineProposal(val id: String, val summary: String, val changeCount: Int, val status: ChangeStatus?, val pending: Boolean) {
    val statusLabel: String
        get() = when {
            pending -> if (changeCount == 1) "1 change waiting for you" else "$changeCount changes waiting for you"
            status == ChangeStatus.REJECTED -> "Dropped"
            status == ChangeStatus.APPLIED || status == ChangeStatus.VERIFIED -> "Approved and written"
            status == null -> "No longer available"
            else -> status.name.lowercase().replaceFirstChar { it.uppercase() }
        }
}

data class AskTurnUi(val turn: ChatTurn, val proposal: InlineProposal?)

data class AskUi(val turns: List<AskTurnUi>, val draft: String, val busy: Boolean, val error: String?)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AskViewModel @Inject constructor(
    private val assistant: AssistantPort,
    private val changeSets: ChangeSetStore,
) : ViewModel() {
    private val draft = MutableStateFlow("")
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val turns = combine(assistant.observeConversation(), changeSets.observePending()) { t, p -> t to p }.mapLatest { (turns, pending) ->
        val pendingIds = pending.map { it.id }.toSet()
        val loaded = turns.mapNotNull { it.proposalId }.distinct().associateWith { id ->
            pending.firstOrNull { it.id == id } ?: runCatching { changeSets.get(id) }.getOrNull()
        }
        turns.map { turn ->
            AskTurnUi(turn, turn.proposalId?.let { id -> inline(id, loaded[id], id in pendingIds) })
        }
    }

    private fun inline(id: String, cs: ChangeSet?, pending: Boolean) =
        InlineProposal(id, cs?.summary ?: "Proposal", cs?.changes?.size ?: 0, cs?.status, pending)

    val state: StateFlow<AskUi> = combine(turns, draft, busy, error) { t, d, b, e -> AskUi(t, d, b, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskUi(emptyList(), "", false, null))

    fun setDraft(text: String) { draft.value = text }

    fun send() = viewModelScope.launch {
        val text = draft.value.trim()
        if (text.isEmpty() || busy.value) return@launch
        busy.value = true
        error.value = null
        draft.value = ""
        val reply = runCatching { assistant.send(text) }
        busy.value = false
        reply.onFailure {
            error.value = it.message ?: "The assistant did not answer"
            draft.value = text
        }
        reply.onSuccess { r -> r.error?.let { error.value = it } }
    }

    fun clear() = viewModelScope.launch {
        assistant.clear()
        error.value = null
    }

    companion object {
        val quickPrompts = listOf(
            "Halaqa ran over by 1h" to "Halaqa ran over by 1h",
            "Plan tomorrow" to "Plan tomorrow",
            "Copy template" to "Copy the template into next week",
        )

        fun isUser(turn: ChatTurn) = turn.role == ChatRole.USER
    }
}
