package app.planner.insights

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlannedVsDoneTest {
    private val mon = LocalDate.of(2026, 10, 26)
    private val tue = mon.plusDays(1)
    private val wed = mon.plusDays(2)

    @Test
    fun `planned minutes are summed per day, and done ones are the finished tasks`() {
        val items = listOf(
            WorkItem(mon, 90, done = true),
            WorkItem(mon, 60, done = false),
            WorkItem(tue, 120, done = true),
        )
        val rows = PlannedVsDone.rows(items, listOf(mon, tue))
        assertEquals(listOf(150, 120), rows.map { it.plannedMin })
        assertEquals(listOf(90, 120), rows.map { it.doneMin })
        assertEquals(listOf("Mon", "Tue"), rows.map { it.weekday })
    }

    @Test
    fun `a day with nothing planned has no percentage rather than zero`() {
        val rows = PlannedVsDone.rows(listOf(WorkItem(mon, 60, done = true)), listOf(mon, wed))
        assertEquals(100, rows[0].percent)
        assertNull(rows[1].percent)
        assertEquals(0, rows[1].plannedMin)
    }

    @Test
    fun `the percentage is whole and rounds to nearest`() {
        val rows = PlannedVsDone.rows(listOf(WorkItem(mon, 90, true), WorkItem(mon, 60, false), WorkItem(mon, 30, false)), listOf(mon))
        assertEquals(50, rows.single().percent) // 90 of 180
        assertEquals(33, PlannedVsDone.rows(listOf(WorkItem(mon, 100, true), WorkItem(mon, 200, false)), listOf(mon)).single().percent)
    }

    @Test
    fun `items outside the asked days are ignored`() {
        val rows = PlannedVsDone.rows(listOf(WorkItem(mon.minusDays(3), 300, true)), listOf(mon))
        assertEquals(0, rows.single().plannedMin)
    }

    @Test
    fun `the overall figure weighs days by their minutes`() {
        val rows = PlannedVsDone.rows(
            listOf(WorkItem(mon, 100, true), WorkItem(tue, 300, false)),
            listOf(mon, tue, wed),
        )
        assertEquals(25, PlannedVsDone.overallPercent(rows)) // 100 of 400
        assertEquals(400, PlannedVsDone.totalPlannedMin(rows))
        assertNull(PlannedVsDone.overallPercent(PlannedVsDone.rows(emptyList(), listOf(mon))))
    }

    @Test
    fun `bar heights scale to the busiest day`() {
        val rows = PlannedVsDone.rows(listOf(WorkItem(mon, 100, true), WorkItem(tue, 300, true)), listOf(mon, tue, wed))
        assertEquals(listOf(1.0 / 3, 1.0, 0.0), PlannedVsDone.heightFractions(rows))
    }
}
