package app.planner.routines

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertTrue

class PlanNotePromptTest {
    private val date = LocalDate.parse("2026-10-28") // a Wednesday
    private val rows = listOf(
        PlanRow(1, "PintOS", "PintOS", LocalTime.of(9, 30), LocalTime.of(11, 30), dueByTomorrow = false),
        PlanRow(2, "Arabic", null, LocalTime.of(13, 0), LocalTime.of(14, 0), dueByTomorrow = true),
    )
    private val forecast = ForecastNote(LocalTime.of(0, 15), 78, LocalTime.of(1, 27), 52)

    @Test
    fun `the prompt lists tomorrow's blocks and the forecast`() {
        val text = PlanNotePrompt.build(date, rows, forecast)
        assertTrue("Wed 28 Oct" in text)
        assertTrue("09:30-11:30 PintOS" in text)
        assertTrue("13:00-14:00 Arabic (due tomorrow)" in text)
        assertTrue("00:15" in text && "78" in text && "01:27" in text && "52" in text)
    }

    @Test
    fun `the prompt asks for a short read-only note`() {
        val text = PlanNotePrompt.build(date, rows, forecast)
        assertTrue("read-only" in text)
        assertTrue("45 words" in text)
    }

    @Test
    fun `an empty day and no forecast are still described`() {
        val text = PlanNotePrompt.build(date, emptyList(), forecast = null)
        assertTrue("nothing planned" in text)
        assertTrue("No forecast" in text)
    }
}
