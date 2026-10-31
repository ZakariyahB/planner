package app.planner.engine.checkin

import app.planner.engine.model.EventRef
import app.planner.engine.model.Prayer
import java.time.LocalDateTime

// Check-ins (newplan section 10): notifications with actions at the start and end of each task block.
// Everything here is pure; :app turns a CheckIn into an exact alarm and, when it fires, a notification.

enum class CheckInKind {
    /** "Lecture - OS, 18:00" with Start / Snooze 10 / Skip. */
    START,

    /** "PintOS done?" with Done / Need 30 more / Stop here. */
    END,

    /** Back-to-back blocks: one ping at the boundary instead of an end ping plus a start ping. */
    NEXT_UP,

    /** A block spanning a prayer: "Dhuhr break, 15 min" at the prayer time. No actions. */
    PRAYER_BREAK,
}

enum class CheckInAction {
    START,
    SNOOZE,
    SKIP,
    DONE,

    /** "Need 30 more": logs the overrun and asks the engine for a replan ChangeSet (needs approval). */
    EXTEND,

    /** "Stop here": logs the stop and asks the engine for a replan of what is left (needs approval). */
    STOP,

    /** On a "Next up" ping: the previous block is done and the next one starts now. */
    DONE_START_NEXT,
}

/** The block a check-in is about, as it was planned when the alarm was set. */
data class CheckInBlock(
    val ref: EventRef,
    val taskId: String?,
    /** Display title including the chunk label, e.g. "PintOS (1/2)". */
    val title: String,
    val plannedStart: LocalDateTime,
    val plannedEnd: LocalDateTime,
    val prayerBufferMin: Int = 0,
    val coveredPrayers: List<Prayer> = emptyList(),
) {
    /** Stable per calendar event, so moving the block replaces its alarms instead of adding new ones. */
    val key: String get() = "${ref.calendarId}/${ref.eventId}"

    /** Minutes of work, without the prayer buffer. */
    val workMin: Int get() = (java.time.Duration.between(plannedStart, plannedEnd).toMinutes() - prayerBufferMin).toInt()
}

data class CheckIn(
    /** Stable: the same block and kind always get the same id, so a reschedule replaces rather than duplicates. */
    val id: String,
    val kind: CheckInKind,
    val at: LocalDateTime,
    val block: CheckInBlock,
    /** For [CheckInKind.NEXT_UP]: the block that starts now. */
    val next: CheckInBlock? = null,
    /** For [CheckInKind.PRAYER_BREAK]. */
    val prayer: Prayer? = null,
    /** Set when the ping was moved out of a prayer slot; the original time. */
    val shiftedFrom: LocalDateTime? = null,
) {
    val actions: List<CheckInAction>
        get() = when (kind) {
            CheckInKind.START -> listOf(CheckInAction.START, CheckInAction.SNOOZE, CheckInAction.SKIP)
            CheckInKind.END -> listOf(CheckInAction.DONE, CheckInAction.EXTEND, CheckInAction.STOP)
            CheckInKind.NEXT_UP -> listOf(CheckInAction.DONE_START_NEXT, CheckInAction.EXTEND, CheckInAction.STOP)
            CheckInKind.PRAYER_BREAK -> emptyList()
        }

    companion object {
        fun idFor(kind: CheckInKind, block: CheckInBlock, prayer: Prayer? = null): String = when (kind) {
            CheckInKind.START -> "start:${block.key}"
            CheckInKind.END -> "end:${block.key}"
            CheckInKind.NEXT_UP -> "next:${block.key}"
            CheckInKind.PRAYER_BREAK -> "prayer:${block.key}:${prayer?.name}"
        }

        /** A snoozed start ping lives under its own id so a reschedule (which drops past pings) does not cancel it. */
        fun snoozeId(block: CheckInBlock): String = "snooze:${block.key}"
    }
}
