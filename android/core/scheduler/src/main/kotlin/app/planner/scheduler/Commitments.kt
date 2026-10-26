package app.planner.scheduler

import app.planner.calendar.FixedEvent
import java.time.ZoneId

/**
 * A commitment added in chat is stored as a locked FIXED block. The planner sees it as a fixed event, so
 * it counts towards a packed day, blocks validation and is drawn like any calendar commitment.
 */
object Commitments {
    /** The "calendar" of commitment events, so they can be told apart from Google Calendar events. */
    const val CALENDAR_ID = "commitment"

    fun isCommitment(block: Block): Boolean = block.kind == BlockKind.FIXED

    fun isCommitmentEvent(event: FixedEvent): Boolean = event.calendarId == CALENDAR_ID

    fun toFixedEvent(block: Block, zone: ZoneId): FixedEvent = FixedEvent(
        id = "commitment-${block.id}",
        calendarId = CALENDAR_ID,
        title = block.label?.takeIf { it.isNotBlank() } ?: "Commitment",
        start = block.date.atTime(block.start).atZone(zone).toInstant(),
        end = block.date.atTime(block.end).atZone(zone).toInstant(),
    )
}
