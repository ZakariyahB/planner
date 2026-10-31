package app.planner.ui.approval

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.engine.changeset.AppliedRecord
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.Settings
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.SnapshotStore
import app.planner.gcal.ApprovalGate
import app.planner.gcal.CalendarException
import app.planner.gcal.CalendarWriter
import app.planner.ui.model.ApplyReport
import app.planner.ui.model.ApprovalCards
import app.planner.ui.model.ChangeSetUi
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
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

/** What the approval sheet / screen shows. */
data class ApprovalUi(
    val changeSet: ChangeSet?,
    val cards: ChangeSetUi?,
    /** Change id to approved; missing means approved (the default). */
    val decisions: Map<String, Boolean>,
    val scopes: Map<String, RecurrenceScope>,
    val report: ApplyReport?,
    /** What was applied, for Undo. */
    val record: AppliedRecord?,
    val busy: Boolean,
    /** Other proposals still waiting after this one. */
    val otherPending: Int,
    val error: String?,
) {
    val selectedCount: Int get() = changeSet?.changes?.count { it.status == ChangeStatus.PROPOSED && decisions[it.id] != false } ?: 0
    val total: Int get() = changeSet?.changes?.count { it.status == ChangeStatus.PROPOSED } ?: 0

    /** One change: a bottom sheet. Several: the full screen list. */
    val asSheet: Boolean get() = (changeSet?.changes?.size ?: 0) <= 1 && (report?.lines?.size ?: 0) <= 1
}

sealed interface ApprovalEvent {
    /** After apply: snackbar text, with Undo for [record]. */
    data class Applied(val text: String, val record: AppliedRecord?) : ApprovalEvent
    data class Message(val text: String) : ApprovalEvent
}

/**
 * The approval path. This is the only caller of [ApprovalGate] in the app: it turns the user's per-change decisions
 * into an ApprovedChangeSet and hands it to [CalendarWriter]. Nothing else in the UI can write to Google Calendar.
 */
@HiltViewModel
class ApprovalViewModel @Inject constructor(
    private val changeSets: ChangeSetStore,
    private val writer: CalendarWriter,
    private val settingsStore: SettingsStore,
    private val snapshots: SnapshotStore,
) : ViewModel() {
    private val events = Channel<ApprovalEvent>(Channel.BUFFERED)
    val messages: Flow<ApprovalEvent> = events.receiveAsFlow()

    private val requestedId = MutableStateFlow<String?>(null)
    private val decisions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val scopes = MutableStateFlow<Map<String, RecurrenceScope>>(emptyMap())
    private val report = MutableStateFlow<Triple<ChangeSet, ApplyReport, AppliedRecord>?>(null)
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val calendarNames = MutableStateFlow<Map<String, String>>(emptyMap())

    private val pending = changeSets.observePending().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val settings = settingsStore.observe().stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private data class Edits(val decisions: Map<String, Boolean>, val scopes: Map<String, RecurrenceScope>, val busy: Boolean, val error: String?)

    private val edits = combine(decisions, scopes, busy, error) { d, s, b, e -> Edits(d, s, b, e) }

    val state: StateFlow<ApprovalUi> = combine(requestedId, pending, settings, edits, combine(report, calendarNames) { r, n -> r to n }) { id, list, s, e, (rep, names) ->
        val cs = rep?.first ?: list.firstOrNull { it.id == id } ?: list.firstOrNull()
        ApprovalUi(
            changeSet = cs,
            cards = cs?.let { ApprovalCards.changeSet(it, s, names) },
            decisions = e.decisions,
            scopes = e.scopes,
            report = rep?.second,
            record = rep?.third,
            busy = e.busy,
            otherPending = list.count { it.id != cs?.id },
            error = e.error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ApprovalUi(null, null, emptyMap(), emptyMap(), null, null, false, 0, null))

    /** Show [changeSetId] (or the newest pending proposal) with every change ticked. */
    fun open(changeSetId: String?) {
        requestedId.value = changeSetId
        decisions.value = emptyMap()
        scopes.value = emptyMap()
        report.value = null
        error.value = null
        viewModelScope.launch {
            val today = LocalDate.now()
            calendarNames.value = runCatching { snapshots.events(today.minusDays(7), today.plusDays(35)) }
                .getOrDefault(emptyList())
                .associate { it.ref.calendarId to it.calendarName.trim() }
        }
    }

    fun close() {
        report.value = null
        requestedId.value = null
    }

    fun setApproved(changeId: String, approved: Boolean) {
        decisions.value = decisions.value + (changeId to approved)
    }

    fun setScope(changeId: String, scope: RecurrenceScope) {
        scopes.value = scopes.value + (changeId to scope)
    }

    fun approve() = viewModelScope.launch {
        val ui = state.value
        val cs = ui.changeSet ?: return@launch
        if (ui.report != null || busy.value) return@launch
        val withScopes = ApprovalCards.withScopes(cs, scopes.value)
        val chosen = withScopes.changes.associate { it.id to (decisions.value[it.id] != false) }
        val approved = ApprovalGate.approve(withScopes, chosen)
        if (approved.approved.isEmpty()) {
            reject()
            return@launch
        }
        busy.value = true
        error.value = null
        try {
            val record = writer.apply(approved)
            val r = ApprovalCards.report(withScopes, record)
            report.value = Triple(withScopes, r, record)
            events.send(ApprovalEvent.Applied(r.snackbar, record.takeIf { r.canUndo }))
        } catch (e: CalendarException.AuthExpired) {
            error.value = "Google sign-in expired, so nothing was written. Sign in again, then approve."
        } catch (e: CalendarException) {
            error.value = (e.message ?: "Google Calendar did not answer") + ". Nothing was written."
        } catch (e: Exception) {
            error.value = "Something went wrong: ${e.message ?: e.javaClass.simpleName}. Check Google Calendar before trying again."
        } finally {
            busy.value = false
        }
    }

    /** Drops the whole proposal. Nothing is written. */
    fun reject() = viewModelScope.launch {
        val cs = state.value.changeSet ?: return@launch
        changeSets.save(cs.copy(status = ChangeStatus.REJECTED, changes = cs.changes.map { it.copy(status = ChangeStatus.REJECTED) }))
        events.send(ApprovalEvent.Message("Dropped. Nothing changed in Google Calendar."))
        close()
    }

    fun undo(record: AppliedRecord) = viewModelScope.launch {
        try {
            writer.undo(record)
            report.value = report.value?.let { (cs, r, rec) ->
                if (rec.changeSetId == record.changeSetId) Triple(cs, r.copy(canUndo = false), rec.copy(undone = true)) else Triple(cs, r, rec)
            }
            events.send(ApprovalEvent.Message("Undone in Google Calendar"))
        } catch (e: Exception) {
            events.send(ApprovalEvent.Message("Could not undo: ${e.message ?: "Google Calendar did not answer"}"))
        }
    }
}
