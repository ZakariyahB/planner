package app.planner.ui.settings

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.engine.model.Settings
import app.planner.engine.model.SyncStamp
import app.planner.engine.ports.AuthState
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.SettingsStore
import app.planner.gcal.GcalAuthOutcome
import app.planner.gcal.GcalAuthorizer
import dagger.hilt.android.lifecycle.HiltViewModel
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

data class SettingsUi(val settings: Settings, val auth: AuthState, val lastSync: SyncStamp?, val busy: Boolean)

@HiltViewModel
class PlannerSettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val queries: PlannerQueries,
) : ViewModel() {
    private val busy = MutableStateFlow(false)

    val state: StateFlow<SettingsUi?> = combine(store.observe(), queries.observeToday(), busy) { s, t, b ->
        SettingsUi(s, t.auth, t.lastSync, b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (Settings) -> Settings) = viewModelScope.launch { store.update(transform) }

    fun refresh() = viewModelScope.launch {
        busy.value = true
        runCatching { queries.refresh() }
        busy.value = false
    }
}

sealed interface SignInEvent {
    data class NeedsConsent(val sender: IntentSender) : SignInEvent
    data class Message(val text: String) : SignInEvent
}

/**
 * Google sign-in for the "Sign in again" banner and Settings, through Stream B's [GcalAuthorizer]. Google's consent
 * screen opens only after the user taps the button.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authorizer: GcalAuthorizer,
    private val queries: PlannerQueries,
) : ViewModel() {
    private val events = Channel<SignInEvent>(Channel.BUFFERED)
    val messages: Flow<SignInEvent> = events.receiveAsFlow()

    fun signIn() = viewModelScope.launch { handle(runCatching { authorizer.authorize() }) }

    fun onConsentResult(data: Intent?) = viewModelScope.launch { handle(runCatching { authorizer.completeConsent(data) }) }

    private suspend fun handle(result: Result<GcalAuthOutcome>) {
        val outcome = result.getOrElse { GcalAuthOutcome.Failed(it.message ?: "Sign-in failed") }
        when (outcome) {
            is GcalAuthOutcome.Authorized -> {
                val stamp = runCatching { queries.refresh() }.getOrNull()
                events.send(SignInEvent.Message(if (stamp?.ok != false) "Signed in to Google Calendar" else "Signed in, but the sync failed: ${stamp.message ?: "try again"}"))
            }
            is GcalAuthOutcome.NeedsConsent -> events.send(SignInEvent.NeedsConsent(outcome.sender))
            is GcalAuthOutcome.Failed -> events.send(SignInEvent.Message(outcome.message))
        }
    }
}
