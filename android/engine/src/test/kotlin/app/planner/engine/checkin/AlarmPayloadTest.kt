package app.planner.engine.checkin

import app.planner.engine.model.EventRef
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.Prayer
import app.planner.engine.nudge.Nudge
import app.planner.engine.nudge.NudgeKind
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmPayloadTest {
    private val t = LocalDateTime.of(2026, 11, 2, 11, 30)
    private val a = CheckInBlock(EventRef("cal-academic", "ps"), "ps-sheet-mon", "P&S problem sheet", t.minusHours(2), t)
    private val b = CheckInBlock(EventRef("cal-lectures", "cp2"), null, "Lecture - CP2 \"recorded\" (1/2)", t, t.plusMinutes(75), 15, listOf(Prayer.DHUHR))

    @Test
    fun `every check-in kind survives the round trip`() {
        val all = listOf(
            CheckIn("start:x", CheckInKind.START, t.minusHours(2), a),
            CheckIn("end:x", CheckInKind.END, t.plusMinutes(34), a, shiftedFrom = t),
            CheckIn("next:x", CheckInKind.NEXT_UP, t, a, next = b),
            CheckIn("prayer:x", CheckInKind.PRAYER_BREAK, t.plusMinutes(19), b, prayer = Prayer.DHUHR),
        )
        for (c in all) {
            val p = AlarmPayload.CheckInAlarm(c)
            assertEquals(p, AlarmPayload.decode(AlarmPayload.encode(p)))
        }
    }

    @Test
    fun `a nudge survives the round trip`() {
        val p = AlarmPayload.NudgeAlarm(Nudge("nudge:x", NudgeKind.WEEKLY_FAJR, t, LocalDate.of(2026, 11, 9)))
        assertEquals(p, AlarmPayload.decode(AlarmPayload.encode(p)))
        assertEquals("nudge:x", p.id)
    }

    @Test
    fun `a block survives on its own`() {
        assertEquals(b, AlarmPayload.decodeBlock(AlarmPayload.encodeBlock(b)))
    }

    @Test
    fun `unreadable payloads are null, never a crash`() {
        for (text in listOf(null, "", "{", "{\"type\":\"other\",\"id\":\"x\",\"kind\":\"START\",\"at\":\"x\"}", "{\"type\":\"checkin\",\"id\":\"x\",\"kind\":\"NOPE\",\"at\":\"2026-11-02T10:00\"}")) {
            assertNull(AlarmPayload.decode(text), text.toString())
        }
        assertNull(AlarmPayload.decodeBlock("[]"))
    }

    @Test
    fun `unknown keys are ignored`() {
        val text = AlarmPayload.encode(AlarmPayload.CheckInAlarm(CheckIn("s", CheckInKind.START, t, a))).replaceFirst("{", "{\"future\":1,")
        assertEquals("s", AlarmPayload.decode(text)?.id)
    }

    // ---- still due when it fires ----

    private fun log(outcome: ExecutionOutcome) = CheckInLog.fresh(a).copy(outcome = outcome)

    @Test
    fun `a start ping is dropped once the block was started or finished in the app`() {
        val start = CheckIn("s", CheckInKind.START, a.plannedStart, a)
        assertTrue(start.stillDue { null })
        assertTrue(start.stillDue { log(ExecutionOutcome.PENDING) })
        for (o in listOf(ExecutionOutcome.STARTED, ExecutionOutcome.EXTENDED, ExecutionOutcome.DONE, ExecutionOutcome.SKIPPED, ExecutionOutcome.STOPPED)) {
            assertFalse(start.stillDue { log(o) }, o.name)
        }
    }

    @Test
    fun `an end ping stands while the block is started, not once it is done`() {
        val end = CheckIn("e", CheckInKind.END, a.plannedEnd, a)
        assertTrue(end.stillDue { log(ExecutionOutcome.STARTED) })
        assertFalse(end.stillDue { log(ExecutionOutcome.DONE) })
        val next = CheckIn("n", CheckInKind.NEXT_UP, a.plannedEnd, a, next = b)
        assertTrue(next.stillDue { if (it == a) log(ExecutionOutcome.STARTED) else null })
        assertFalse(next.stillDue { if (it == a) log(ExecutionOutcome.STOPPED) else null })
    }
}
