package app.planner.social

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocialBudgetTest {
    private val budget = SocialBudget()

    @Test
    fun `weekdays get two hours and weekends three`() {
        assertEquals(120, budget.minutesFor(LocalDate.of(2026, 10, 26))) // Monday
        assertEquals(120, budget.minutesFor(LocalDate.of(2026, 10, 30))) // Friday
        assertEquals(180, budget.minutesFor(LocalDate.of(2026, 10, 31))) // Saturday
        assertEquals(180, budget.minutesFor(LocalDate.of(2026, 11, 1))) // Sunday
    }

    @Test
    fun `the budgets can be changed`() {
        val custom = SocialBudget(weekdayMin = 90, weekendMin = 240)
        assertEquals(90, custom.minutesFor(LocalDate.of(2026, 10, 27)))
        assertEquals(240, custom.minutesFor(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun `a budget must be positive`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { SocialBudget(weekdayMin = 0) }
    }

    @Test
    fun `nothing is due until half the budget is used`() {
        assertNull(SocialNudges.due(usedMin = 59, budgetMin = 120, alreadySent = emptySet()))
        assertEquals(Nudge.HALF, SocialNudges.due(usedMin = 60, budgetMin = 120, alreadySent = emptySet()))
    }

    @Test
    fun `thresholds are 50, 80 and 100 percent`() {
        assertEquals(Nudge.MOST, SocialNudges.due(96, 120, emptySet())) // 80% of 120
        assertEquals(Nudge.FULL, SocialNudges.due(120, 120, emptySet()))
        assertEquals(Nudge.FULL, SocialNudges.due(200, 120, emptySet()))
    }

    @Test
    fun `a nudge is only sent once, and only the highest crossed one is due`() {
        assertEquals(Nudge.FULL, SocialNudges.due(130, 120, emptySet())) // jumped past 50 and 80
        assertNull(SocialNudges.due(130, 120, SocialNudges.markSent(Nudge.FULL, emptySet())))
        assertNull(SocialNudges.due(70, 120, setOf(Nudge.HALF)))
        assertEquals(Nudge.MOST, SocialNudges.due(100, 120, setOf(Nudge.HALF)))
    }

    @Test
    fun `sending a nudge also counts the lower ones as sent`() {
        assertEquals(setOf(Nudge.HALF, Nudge.MOST, Nudge.FULL), SocialNudges.markSent(Nudge.FULL, emptySet()))
        assertEquals(setOf(Nudge.HALF), SocialNudges.markSent(Nudge.HALF, emptySet()))
    }

    @Test
    fun `the percentage used is whole and can pass 100`() {
        assertEquals(50, SocialNudges.percent(60, 120))
        assertEquals(150, SocialNudges.percent(180, 120))
        assertEquals(0, SocialNudges.percent(0, 120))
    }

    @Test
    fun `nudges are gentle and say what is left or over`() {
        assertTrue("1h 00m" in SocialNudges.message(Nudge.HALF, 60, 120))
        val most = SocialNudges.message(Nudge.MOST, 96, 120)
        assertTrue("24 minutes left" in most, most)
        val full = SocialNudges.message(Nudge.FULL, 125, 120)
        assertTrue("5 minutes over" in full, full)
        assertTrue("2h 00m" in SocialNudges.message(Nudge.FULL, 120, 120))
    }

    @Test
    fun `the daily summary compares with the budget`() {
        assertTrue("1h 30m" in SocialNudges.summary(90, 120) && "30 minutes under" in SocialNudges.summary(90, 120))
        assertTrue("20 minutes over" in SocialNudges.summary(140, 120))
        assertTrue("on budget" in SocialNudges.summary(120, 120))
    }
}
