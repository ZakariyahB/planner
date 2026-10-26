package app.planner.routines

import app.planner.readiness.BackToBedAdvice
import app.planner.readiness.Readiness
import app.planner.readiness.ReadinessBand
import app.planner.readiness.ReadinessInputs
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationTextsTest {
    private fun readiness(score: Int, asleepMin: Int = 420) = Readiness(
        score,
        ReadinessBand.of(score),
        ReadinessInputs(asleepMin = asleepMin, debtMin = 0, lightsOutDelayMin = 0, doneRatio = null),
    )

    private val alarm = LocalTime.of(8, 45)

    @Test
    fun `durations read as hours and minutes`() {
        assertEquals("3h 30m", NotificationTexts.duration(210))
        assertEquals("3h", NotificationTexts.duration(180))
        assertEquals("45m", NotificationTexts.duration(45))
        assertEquals("1h 5m", NotificationTexts.duration(65))
        assertEquals("0m", NotificationTexts.duration(0))
    }

    @Test
    fun `provisional readiness says how long so far and when to set the alarm`() {
        val t = NotificationTexts.provisionalReadiness(210, readiness(52, 210), BackToBedAdvice(210, alarm))
        assertEquals("Back to bed", t.title)
        assertEquals("3h 30m so far, back to bed, alarm 08:45. Aim for up to 3h 30m more. Readiness so far: 52.", t.body)
    }

    @Test
    fun `provisional readiness with enough sleep does not send him back to bed`() {
        val t = NotificationTexts.provisionalReadiness(440, readiness(80, 440), BackToBedAdvice(0, alarm))
        assertEquals("Enough sleep", t.title)
        assertEquals("7h 20m so far, that's enough. Keep the alarm at 08:45. Readiness so far: 80.", t.body)
    }

    @Test
    fun `provisional readiness after the alarm time says the final score comes later`() {
        val t = NotificationTexts.provisionalReadiness(210, readiness(52, 210), advice = null)
        assertEquals("Readiness so far: 52", t.title)
        assertEquals("3h 30m so far and your alarm time has passed. The final score comes once you're up.", t.body)
    }

    @Test
    fun `green morning brief gives the plan`() {
        val outline = DayOutline(taskCount = 5, firstStart = LocalTime.of(9, 30), firstTitle = "PintOS")
        val t = NotificationTexts.morningBrief(readiness(82, 440), outline, loadCutCount = 0)
        assertEquals("Readiness 82: full plan today", t.title)
        assertEquals("Slept 7h 20m. 5 tasks planned, first PintOS at 09:30.", t.body)
    }

    @Test
    fun `amber morning brief mentions the lighter day and what moving tasks would do`() {
        val outline = DayOutline(taskCount = 5, firstStart = LocalTime.of(9, 30), firstTitle = "PintOS")
        val t = NotificationTexts.morningBrief(readiness(58, 320), outline, loadCutCount = 2)
        assertEquals("Readiness 58: lighter day suggested", t.title)
        assertEquals(
            "Slept 5h 20m. 5 tasks planned, first PintOS at 09:30. Moving 2 tasks off today would make it about 20% lighter; open the app to review.",
            t.body,
        )
    }

    @Test
    fun `red morning brief with nothing to move still suggests a lighter day`() {
        val outline = DayOutline(taskCount = 1, firstStart = LocalTime.of(11, 0), firstTitle = "OS worksheet")
        val t = NotificationTexts.morningBrief(readiness(31, 240), outline, loadCutCount = 0)
        assertEquals("Readiness 31: low-energy day", t.title)
        assertEquals("Slept 4h. 1 task planned, first OS worksheet at 11:00. Keep to what is due within 48 hours.", t.body)
    }

    @Test
    fun `morning brief with nothing planned says so`() {
        val t = NotificationTexts.morningBrief(readiness(90), DayOutline(0, null, null), loadCutCount = 0)
        assertEquals("Slept 7h. Nothing planned today.", t.body)
    }

    private val today = LocalDate.parse("2026-10-26") // a Monday

    @Test
    fun `work stops with everything done`() {
        val t = NotificationTexts.workStops(WorkStopsSummary(done = 6, planned = 6, moved = emptyList(), today = today))
        assertEquals("Work stops", t.title)
        assertEquals("All 6 tasks done.", t.body)
    }

    @Test
    fun `work stops lists what moved and where`() {
        val moved = listOf(
            MovedTask("PintOS", LocalDate.parse("2026-10-27")),
            MovedTask("ADA", LocalDate.parse("2026-10-29")),
            MovedTask("SDE", null),
        )
        val t = NotificationTexts.workStops(WorkStopsSummary(done = 3, planned = 6, moved = moved, today = today))
        assertEquals("3 of 6 tasks done. Moved: PintOS to tomorrow, ADA to Thu, SDE doesn't fit this week.", t.body)
    }

    @Test
    fun `work stops with unfinished tasks that did not move`() {
        val t = NotificationTexts.workStops(WorkStopsSummary(done = 1, planned = 2, moved = emptyList(), today = today))
        assertEquals("1 of 2 tasks done. Nothing moved.", t.body)
    }

    @Test
    fun `work stops on a day with nothing planned`() {
        val t = NotificationTexts.workStops(WorkStopsSummary(done = 0, planned = 0, moved = emptyList(), today = today))
        assertEquals("Nothing was planned today.", t.body)
    }

    @Test
    fun `a single task is not pluralised`() {
        val t = NotificationTexts.workStops(WorkStopsSummary(done = 1, planned = 1, moved = emptyList(), today = today))
        assertEquals("The task is done.", t.body)
    }

    @Test
    fun `plan ready gives tomorrow and compares on-time lights-out with the usual one`() {
        val outline = DayOutline(taskCount = 4, firstStart = LocalTime.of(9, 15), firstTitle = "Arabic")
        val forecast = ForecastNote(onTimeLightsOut = LocalTime.of(0, 15), onTimeScore = 78, usualLightsOut = LocalTime.of(1, 27), usualScore = 52)
        val t = NotificationTexts.planReady(outline, forecast)
        assertEquals("Tomorrow's plan is ready", t.title)
        assertEquals(
            "4 tasks, first Arabic at 09:15. Lights-out at 00:15 gives readiness 78; your usual 01:27 gives 52.",
            t.body,
        )
    }

    @Test
    fun `plan ready without a usual lights-out only gives the on-time forecast`() {
        val outline = DayOutline(taskCount = 1, firstStart = LocalTime.of(9, 15), firstTitle = "Arabic")
        val forecast = ForecastNote(LocalTime.of(0, 15), 78, usualLightsOut = null, usualScore = null)
        assertEquals("1 task, first Arabic at 09:15. Lights-out at 00:15 gives readiness 78.", NotificationTexts.planReady(outline, forecast).body)
    }

    @Test
    fun `plan ready without a forecast or tasks still reads sensibly`() {
        assertEquals("Nothing planned tomorrow.", NotificationTexts.planReady(DayOutline(0, null, null), null).body)
    }

    @Test
    fun `the forecast line is the one shared by the notification and the sheet`() {
        val f = ForecastNote(LocalTime.of(0, 15), 78, LocalTime.of(1, 27), 52)
        assertEquals("Lights-out at 00:15 gives readiness 78; your usual 01:27 gives 52.", NotificationTexts.forecastLine(f))
        assertEquals("Lights-out at 00:15 gives readiness 78.", NotificationTexts.forecastLine(f.copy(usualLightsOut = null, usualScore = null)))
    }

    @Test
    fun `lights-out warning gives the time in bed`() {
        val t = NotificationTexts.lightsOutSoon(lightsOut = LocalTime.of(0, 15), wake = alarm)
        assertEquals("Lights-out in 30 minutes", t.title)
        assertEquals("Lights-out is 00:15, which gives 8h 30m in bed before 08:45. Start winding down.", t.body)
    }

    @Test
    fun `lights-out warning handles a lights-out before midnight`() {
        val t = NotificationTexts.lightsOutSoon(lightsOut = LocalTime.of(23, 0), wake = alarm)
        assertEquals("Lights-out is 23:00, which gives 9h 45m in bed before 08:45. Start winding down.", t.body)
    }

    @Test
    fun `sunday review prompt reports the week and asks for the plan for next`() {
        val t = NotificationTexts.sundayReview(plannedMin = 1440, doneMin = 1080)
        assertEquals("Sunday review", t.title)
        assertEquals("You did 18h of the 24h planned this week (75%). Take a few minutes to plan next week.", t.body)
    }

    @Test
    fun `sunday review prompt with nothing planned does not divide by zero`() {
        val t = NotificationTexts.sundayReview(plannedMin = 0, doneMin = 0)
        assertEquals("Take a few minutes to plan next week.", t.body)
    }

    @Test
    fun `no text is empty or shouts`() {
        val all = listOf(
            NotificationTexts.lightsOutSoon(LocalTime.of(0, 15), alarm),
            NotificationTexts.sundayReview(60, 30),
            NotificationTexts.planReady(DayOutline(0, null, null), null),
        )
        for (t in all) {
            assertTrue(t.title.isNotBlank() && t.body.isNotBlank())
            assertFalse(t.body.contains("!"))
        }
    }
}
