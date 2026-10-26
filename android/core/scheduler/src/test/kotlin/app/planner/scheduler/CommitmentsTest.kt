package app.planner.scheduler

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A commitment added in chat ("Interview Thu 14:00-15:00") is a locked block the planner treats as a fixed event. */
class CommitmentsTest {
    private val interview = Block(
        id = 7, date = date("2026-10-29"), start = time("14:00"), end = time("15:00"),
        kind = BlockKind.FIXED, locked = true, label = "Interview", origin = BlockOrigin.AI,
    )

    @Test
    fun `a commitment becomes a fixed event at its local time`() {
        val event = Commitments.toFixedEvent(interview, london)
        assertEquals("Interview", event.title)
        assertEquals(instant("2026-10-29", "14:00"), event.start)
        assertEquals(instant("2026-10-29", "15:00"), event.end)
        assertEquals(Commitments.CALENDAR_ID, event.calendarId)
        assertEquals("commitment-7", event.id)
    }

    @Test
    fun `an unlabelled commitment still has a name`() {
        assertEquals("Commitment", Commitments.toFixedEvent(interview.copy(label = null), london).title)
        assertEquals("Commitment", Commitments.toFixedEvent(interview.copy(label = "  "), london).title)
    }

    @Test
    fun `only fixed blocks are commitments`() {
        assertTrue(Commitments.isCommitment(interview))
        assertFalse(Commitments.isCommitment(interview.copy(kind = BlockKind.TASK)))
    }

    @Test
    fun `the planner works around a commitment like any fixed event`() {
        val ctx = DayContext(date("2026-10-29"), london, fixed = listOf(Commitments.toFixedEvent(interview, london)))
        val plan = Scheduler().plan(ctx, listOf(task(1, "A", 300)))
        // 08:45-14:00 is 315 minutes, so a 300 minute task fits before the interview
        assertEquals("08:45-13:45", plan.blocks.single().span())
        val later = Scheduler().plan(ctx, listOf(task(1, "A", 120), task(2, "B", 120)))
        assertTrue(later.blocks.none { it.start < time("15:15") && it.end > time("14:00") })
    }

    @Test
    fun `commitment events are told apart from calendar events`() {
        val event = Commitments.toFixedEvent(interview, london)
        assertTrue(Commitments.isCommitmentEvent(event))
        assertFalse(Commitments.isCommitmentEvent(event.copy(calendarId = "primary")))
        assertEquals(Instant.parse("2026-10-29T14:00:00Z"), event.start) // GMT in late October
    }
}
