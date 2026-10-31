package app.planner.gcal

import app.planner.engine.changeset.AppliedRecord
import app.planner.engine.changeset.Change
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.EventRef
import app.planner.engine.ports.CalendarReader

// Contracts (Stream 0). Implemented by Stream B. Change only through docs/redesign/CHANGES.md.
//
// HARD RULE, enforced by types and module boundaries:
//  - The only code that writes to Google Calendar is CalendarWriter.apply(ApprovedChangeSet) (and its undo).
//  - ApprovedChangeSet has an internal constructor; only ApprovalGate (the approval UI path) makes one.
//  - :assistant does not depend on this module, so the AI has no reference to CalendarWriter or the gate.

/** Read and write primitives. Implemented over Google Calendar REST v3 in :app, and in memory for tests. */
interface CalendarGateway : CalendarReader {
    /** [eventId] is client-generated so a retried insert is idempotent (a second insert with the same id is a no-op). */
    suspend fun insert(calendarId: String, eventId: String, draft: EventDraft): EventSnapshot

    /** Moving between calendars is a move + patch; the gateway handles it. */
    suspend fun patch(ref: EventRef, draft: EventDraft, scope: RecurrenceScope): EventSnapshot

    suspend fun delete(ref: EventRef, scope: RecurrenceScope)

    /** Incremental read. Throws [CalendarException.SyncTokenGone] on HTTP 410; the caller then does a full re-fetch. */
    suspend fun changesSince(calendarId: String, syncToken: String?): IncrementalPage
}

data class IncrementalPage(val changed: List<EventSnapshot>, val deletedRefs: List<EventRef>, val nextSyncToken: String?)

sealed class CalendarException(message: String) : Exception(message) {
    /** Refresh token expired (7-day limit while the OAuth consent screen is in Testing). Show "Sign in again". */
    class AuthExpired : CalendarException("Google sign-in expired")
    class NotFound(val ref: EventRef) : CalendarException("Event not found: ${ref.eventId}")
    class SyncTokenGone(val calendarId: String) : CalendarException("Sync token expired for $calendarId")
    class ReadOnlyCalendar(val calendarId: String) : CalendarException("Calendar is read-only: $calendarId")
    class Network(cause: Throwable) : CalendarException(cause.message ?: "Network error")
}

/** A ChangeSet the user approved, holding only the changes they approved. Cannot be built outside this module. */
class ApprovedChangeSet internal constructor(
    val changeSet: ChangeSet,
    val approved: List<Change>,
)

/**
 * The approval UI's only path to a writable ChangeSet. Rejected changes are dropped; an empty approval writes nothing.
 * [decisions] maps change id to approved (true) or rejected (false); missing ids count as rejected.
 */
object ApprovalGate {
    fun approve(changeSet: ChangeSet, decisions: Map<String, Boolean>): ApprovedChangeSet {
        val approved = changeSet.changes
            .filter { decisions[it.id] == true && it.status == ChangeStatus.PROPOSED }
            .map { it.copy(status = ChangeStatus.APPROVED) }
        return ApprovedChangeSet(changeSet, approved)
    }

    fun approveAll(changeSet: ChangeSet): ApprovedChangeSet =
        approve(changeSet, changeSet.changes.associate { it.id to true })
}

interface CalendarWriter {
    /**
     * newplan 7.6 to 7.8: pre-flight (re-fetch each target; etag or updated changed means STALE, no write), apply with
     * client ids and planner tags, verify by re-reading. Returns per-change status and the inverse ops for undo,
     * and saves the record.
     */
    suspend fun apply(approved: ApprovedChangeSet): AppliedRecord

    /** Undo the last applied ChangeSet (re-add removed, revert edited, delete added). */
    suspend fun undo(record: AppliedRecord): AppliedRecord
}
