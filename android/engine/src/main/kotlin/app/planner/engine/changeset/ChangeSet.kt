package app.planner.engine.changeset

import app.planner.engine.model.EventRef
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

// Contracts (Stream 0). Change only through docs/redesign/CHANGES.md.
//
// Every calendar write goes through a ChangeSet, whatever its source. A ChangeSet is data: building one writes nothing.
// The only way to write is :calendar's CalendarWriter.apply(ApprovedChangeSet), and only ApprovalGate makes those.

/** extendedProperties.private keys on app-created events. They link events to tasks; they do not limit what may be edited. */
object PlannerTags {
    const val PLANNER = "planner"
    const val TASK_ID = "taskId"
    const val CHUNK = "chunk"
    const val REV = "rev"
}

sealed interface EventTime {
    data class Timed(val start: LocalDateTime, val end: LocalDateTime) : EventTime

    /** [endExclusive] follows Google: a one-day event ends the next day. */
    data class AllDay(val start: LocalDate, val endExclusive: LocalDate) : EventTime
}

/** An event exactly as read from Google, used to show the target and to detect staleness before writing. */
data class EventSnapshot(
    val ref: EventRef,
    val calendarName: String,
    val title: String,
    val time: EventTime,
    val description: String? = null,
    val etag: String,
    val updated: Instant,
    /** Set when this is an instance of a recurring series. */
    val recurringEventId: String? = null,
    val organiserIsExternal: Boolean = false,
    val privateProps: Map<String, String> = emptyMap(),
) {
    val isPlannerBlock: Boolean get() = privateProps[PlannerTags.PLANNER] == "1"
    val taskId: String? get() = privateProps[PlannerTags.TASK_ID]
}

/** The state an event should have after a change. */
data class EventDraft(
    val calendarId: String,
    val title: String,
    val time: EventTime,
    val description: String? = null,
    val privateProps: Map<String, String> = emptyMap(),
)

enum class ChangeType { ADD, MOVE, EDIT, REMOVE }

enum class ChangeFlag {
    TOUCHES_FIXED_EVENT,
    /** Organised by someone else (e.g. the teacher-set Arabic lesson). */
    EXTERNAL_ORGANISER,
    USES_REST_ZONE,
    EXTENDED_DAY,
    OVER_SOFT_LIMIT,
    INTERPOLATED_PRAYER,
    RECURRING,
    MISSES_DEADLINE,
    /** Converts an all-day template event into a timed block. */
    CONVERTS_ALL_DAY,
}

/** Recurring events: the card states the scope explicitly. Default: this instance. */
enum class RecurrenceScope { THIS_INSTANCE, THIS_AND_FOLLOWING, WHOLE_SERIES }

enum class ChangeStatus { PROPOSED, APPROVED, REJECTED, STALE, APPLIED, VERIFIED, FAILED }

enum class ChangeSource { USER, ASSISTANT, REPLAN, ROLLOVER, NOTIFICATION, IMPORT }

/**
 * One proposed edit. ADD has [after] only; REMOVE has [target] only; MOVE and EDIT have both.
 * [target] is the exact resolved event (id, title, calendar, time) shown on the card.
 */
data class Change(
    val id: String,
    val type: ChangeType,
    val target: EventSnapshot? = null,
    val after: EventDraft? = null,
    /** For ADD: the client-generated Google event id, so retries are idempotent. */
    val newEventId: String? = null,
    val flags: Set<ChangeFlag> = emptySet(),
    val scope: RecurrenceScope = RecurrenceScope.THIS_INSTANCE,
    val status: ChangeStatus = ChangeStatus.PROPOSED,
    val taskId: String? = null,
    val chunkIndex: Int? = null,
    /** One line for the card, e.g. "Includes 15 min for Dhuhr". */
    val note: String? = null,
    /** Why it failed or went stale, for the report. */
    val problem: String? = null,
) {
    init {
        when (type) {
            ChangeType.ADD -> require(target == null && after != null) { "ADD needs after only" }
            ChangeType.REMOVE -> require(target != null && after == null) { "REMOVE needs target only" }
            ChangeType.MOVE, ChangeType.EDIT -> require(target != null && after != null) { "$type needs target and after" }
        }
    }
}

data class ChangeSet(
    val id: String,
    val source: ChangeSource,
    /** One sentence for the card header, e.g. "Halaqa ran over: move 2 blocks". */
    val summary: String,
    val changes: List<Change>,
    val createdAt: Instant,
    val status: ChangeStatus = ChangeStatus.PROPOSED,
    /** Things the user should know that are not changes (assumed gaps, overflow to Sunday, missed deadlines). */
    val notes: List<String> = emptyList(),
)

/** How to reverse one applied change. Stored with the applied record so the last ChangeSet can be undone. */
sealed interface InverseOp {
    data class Delete(val ref: EventRef) : InverseOp

    /** Re-create a removed event as it was. */
    data class Restore(val snapshot: EventSnapshot) : InverseOp

    /** Put an edited or moved event back. */
    data class Revert(val ref: EventRef, val before: EventDraft) : InverseOp
}

data class ChangeResult(val changeId: String, val status: ChangeStatus, val ref: EventRef?, val problem: String? = null)

/** What CalendarWriter did with an approved ChangeSet. */
data class AppliedRecord(
    val changeSetId: String,
    val appliedAt: Instant,
    val results: List<ChangeResult>,
    /** In reverse application order. */
    val inverse: List<InverseOp>,
    val undone: Boolean = false,
) {
    val allVerified: Boolean get() = results.all { it.status == ChangeStatus.VERIFIED }
}
