package app.planner.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.ai.AiAssistant
import app.planner.ai.AiException
import app.planner.ai.AiFailure
import app.planner.ai.AiPlanService
import app.planner.ai.AiSpendRepository
import app.planner.ai.ChatItem
import app.planner.ai.ChatRepository
import app.planner.ai.IdTokenProvider
import app.planner.ai.Proposal
import app.planner.ai.Role
import app.planner.ai.TokenResult
import app.planner.plan.PlanMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A problem to show under the conversation; [failure] tells the screen which action to offer. */
data class ChatProblem(val message: String, val failure: AiFailure)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val assistant: AiAssistant,
    private val chat: ChatRepository,
    private val changes: AiPlanService,
    private val tokens: IdTokenProvider,
    spend: AiSpendRepository,
) : ViewModel() {
    val items: StateFlow<List<ChatItem>> = chat.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Changes the assistant made for the user, for the Undo snackbar. */
    val announcements: SharedFlow<PlanMessage> = changes.announcements

    /** This month's AI spend in pounds. */
    val spendGbp: StateFlow<Double> = spend.thisMonthGbp.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** Big proposals waiting for Approve. */
    val proposals: StateFlow<List<Proposal>> = changes.pending

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _problem = MutableStateFlow<ChatProblem?>(null)
    val problem: StateFlow<ChatProblem?> = _problem

    /** Text waiting in the input box, for example a voice transcript or "Change that: ". */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft

    fun setDraft(text: String) {
        _draft.value = text
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        _draft.value = ""
        _problem.value = null
        viewModelScope.launch {
            _busy.value = true
            try {
                val history = chat.history()
                chat.add(Role.USER, trimmed)
                val result = assistant.ask(history, trimmed)
                chat.add(Role.ASSISTANT, result.reply, result.toolCalls)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _problem.value = ChatProblem(e.message ?: "AI unavailable", e.failure)
            } catch (e: Exception) {
                _problem.value = ChatProblem("Something went wrong. Try again.", AiFailure.OTHER)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Opens Google's sign-in; [activity] is needed because it shows a screen. */
    fun signIn(activity: Context) {
        viewModelScope.launch {
            when (val result = tokens.token(activity)) {
                is TokenResult.Token -> _problem.value = null
                TokenResult.NeedsSignIn -> _problem.value = ChatProblem("Sign in was cancelled", AiFailure.SIGN_IN)
                is TokenResult.Unavailable -> _problem.value = ChatProblem(result.message, AiFailure.OTHER)
            }
        }
    }

    fun approve(id: String) {
        viewModelScope.launch { changes.approve(id) }
    }

    fun dismiss(id: String) = changes.reject(id)

    fun clearConversation() {
        viewModelScope.launch { chat.clear() }
    }
}
