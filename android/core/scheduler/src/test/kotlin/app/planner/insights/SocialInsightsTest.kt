package app.planner.insights

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialInsightsTest {
    private val mon = LocalDate.of(2026, 10, 26)

    private fun point(offset: Int, minutes: Int, budget: Int) = SocialPoint(mon.plusDays(offset.toLong()), minutes, budget)

    @Test
    fun `a day is over only when it passes its budget`() {
        val rows = SocialInsights.rows(listOf(point(0, 95, 120), point(1, 120, 120), point(2, 121, 120)))
        assertEquals(listOf(false, false, true), rows.map { it.over })
    }

    @Test
    fun `rows carry weekday and a readable duration`() {
        val row = SocialInsights.rows(listOf(point(0, 95, 120))).single()
        assertEquals("Mon", row.weekday)
        assertEquals("1h 35m", row.label)
        assertEquals(120, row.budgetMin)
    }

    @Test
    fun `days over budget are counted`() {
        val rows = SocialInsights.rows(listOf(point(0, 95, 120), point(1, 140, 120), point(5, 210, 180)))
        assertEquals(2, SocialInsights.daysOver(rows))
    }

    @Test
    fun `the scale covers the largest figure in whole hours`() {
        assertEquals(240, SocialInsights.axisMaxMin(SocialInsights.rows(listOf(point(0, 210, 180)))))
        assertEquals(120, SocialInsights.axisMaxMin(SocialInsights.rows(listOf(point(0, 30, 120)))))
        assertEquals(120, SocialInsights.axisMaxMin(emptyList())) // nothing to scale: two hours
    }

    @Test
    fun `bar and budget marker positions are fractions of the scale`() {
        val rows = SocialInsights.rows(listOf(point(0, 60, 120), point(5, 210, 180)))
        val max = SocialInsights.axisMaxMin(rows)
        assertEquals(240, max)
        assertEquals(0.25, SocialInsights.fraction(rows[0].minutes, max), 1e-9)
        assertEquals(0.5, SocialInsights.fraction(rows[0].budgetMin, max), 1e-9)
        assertEquals(0.875, SocialInsights.fraction(rows[1].minutes, max), 1e-9)
    }

    @Test
    fun `no data is not over budget`() {
        assertFalse(SocialInsights.rows(listOf(point(0, 0, 120))).single().over)
        assertTrue(SocialInsights.rows(emptyList()).isEmpty())
    }
}
