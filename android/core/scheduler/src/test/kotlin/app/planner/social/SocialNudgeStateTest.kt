package app.planner.social

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocialNudgeStateTest {
    private val monday = LocalDate.of(2026, 10, 26)
    private val budget = SocialBudget() // 120 weekdays, 180 weekends

    private fun at(time: String, date: LocalDate = monday) = LocalDateTime.of(date, LocalTime.parse(time))

    private fun decide(
        used: Int,
        now: LocalDateTime = at("14:00"),
        sent: SocialSent? = null,
        nudges: Boolean = true,
        summary: Boolean = true,
    ) = SocialNudgeState.decide(now, used, budget, sent, nudgesEnabled = nudges, summaryEnabled = summary)

    @Test
    fun `nothing to send while under half the budget`() {
        val d = decide(used = 59)
        assertNull(d.nudge)
        assertFalse(d.summary)
    }

    @Test
    fun `the first threshold crossed is sent and remembered`() {
        val d = decide(used = 60)
        assertEquals(Nudge.HALF, d.nudge)
        assertEquals(setOf(Nudge.HALF), d.sent.nudges)
        assertEquals(monday, d.sent.date)
    }

    @Test
    fun `a nudge is never repeated`() {
        val first = decide(used = 60)
        val again = decide(used = 70, sent = first.sent)
        assertNull(again.nudge)
        assertEquals(first.sent, again.sent)
    }

    @Test
    fun `later thresholds follow one at a time`() {
        val half = decide(used = 60)
        val most = decide(used = 100, sent = half.sent)
        assertEquals(Nudge.MOST, most.nudge)
        val full = decide(used = 125, sent = most.sent)
        assertEquals(Nudge.FULL, full.nudge)
        assertNull(decide(used = 150, sent = full.sent).nudge)
    }

    @Test
    fun `jumping past two thresholds sends only the highest and settles the lower`() {
        val d = decide(used = 130)
        assertEquals(Nudge.FULL, d.nudge)
        assertEquals(setOf(Nudge.HALF, Nudge.MOST, Nudge.FULL), d.sent.nudges)
    }

    @Test
    fun `a new day starts fresh`() {
        val yesterday = SocialSent(monday, setOf(Nudge.HALF, Nudge.MOST, Nudge.FULL), summarySent = true)
        val tuesday = monday.plusDays(1)
        val d = decide(used = 60, now = at("10:00", tuesday), sent = yesterday)
        assertEquals(Nudge.HALF, d.nudge)
        assertEquals(tuesday, d.sent.date)
        assertEquals(setOf(Nudge.HALF), d.sent.nudges)
        assertFalse(d.sent.summarySent)
    }

    @Test
    fun `the budget used is the one for the day`() {
        val saturday = LocalDate.of(2026, 10, 31) // 180 minutes
        assertNull(SocialNudgeState.decide(at("14:00", saturday), 60, budget, null).nudge) // 33%
        assertEquals(Nudge.HALF, SocialNudgeState.decide(at("14:00", saturday), 90, budget, null).nudge)
    }

    @Test
    fun `the summary waits for 21 30`() {
        assertFalse(decide(used = 10, now = at("21:29")).summary)
        assertTrue(decide(used = 10, now = at("21:30")).summary)
        assertTrue(decide(used = 10, now = at("23:50")).summary)
    }

    @Test
    fun `the summary is sent once a day`() {
        val first = decide(used = 10, now = at("21:30"))
        assertTrue(first.sent.summarySent)
        val later = decide(used = 10, now = at("21:45"), sent = first.sent)
        assertFalse(later.summary)
        assertTrue(later.sent.summarySent)
    }

    @Test
    fun `a nudge and the summary can be due together`() {
        val d = decide(used = 125, now = at("21:30"))
        assertEquals(Nudge.FULL, d.nudge)
        assertTrue(d.summary)
    }

    @Test
    fun `turned off nudges are not sent and not used up, so they can still come later`() {
        val off = decide(used = 100, nudges = false)
        assertNull(off.nudge)
        assertTrue(off.sent.nudges.isEmpty())
        assertEquals(Nudge.MOST, decide(used = 100, sent = off.sent).nudge)
    }

    @Test
    fun `a turned off summary is not sent and not used up`() {
        val off = decide(used = 10, now = at("22:00"), summary = false)
        assertFalse(off.summary)
        assertFalse(off.sent.summarySent)
        assertTrue(decide(used = 10, now = at("22:15"), sent = off.sent).summary)
    }

    @Test
    fun `sent markers survive being saved as text`() {
        val sent = SocialSent(monday, setOf(Nudge.HALF, Nudge.MOST), summarySent = true)
        assertEquals("2026-10-26|HALF,MOST|1", sent.encode())
        assertEquals(sent, SocialSent.decode(sent.encode()))
        val empty = SocialSent(monday)
        assertEquals("2026-10-26||0", empty.encode())
        assertEquals(empty, SocialSent.decode(empty.encode()))
    }

    @Test
    fun `unreadable saved markers are treated as nothing sent`() {
        assertNull(SocialSent.decode(null))
        assertNull(SocialSent.decode(""))
        assertNull(SocialSent.decode("garbage"))
        assertNull(SocialSent.decode("not-a-date|HALF|0"))
        assertEquals(SocialSent(monday, setOf(Nudge.HALF)), SocialSent.decode("2026-10-26|HALF,BOGUS|0"))
        assertEquals(Nudge.HALF, decide(used = 60, sent = SocialSent.decode("garbage")).nudge)
    }
}
