package app.planner.setup

import app.planner.scheduler.DayRules
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsSummariesTest {
    @Test
    fun `day rules are summarised by their three main times`() {
        assertEquals("Wake 08:45, work stops 21:00, lights-out 00:15", SettingsSummaries.dayRules(DayRules()))
        val edited = DayRules(wake = LocalTime.of(7, 5), workStop = LocalTime.of(20, 30), lightsOut = LocalTime.of(23, 45))
        assertEquals("Wake 07:05, work stops 20:30, lights-out 23:45", SettingsSummaries.dayRules(edited))
    }

    @Test
    fun `the template is counted, singular and empty included`() {
        assertEquals("6 recurring tasks", SettingsSummaries.template(6))
        assertEquals("1 recurring task", SettingsSummaries.template(1))
        assertEquals("No recurring tasks yet", SettingsSummaries.template(0))
    }

    @Test
    fun `calendars say how many count as commitments, or that none is connected`() {
        assertEquals("3 of 5 count as fixed commitments", SettingsSummaries.calendars(connected = true, selected = 3, total = 5))
        assertEquals("Not connected", SettingsSummaries.calendars(connected = false, selected = 0, total = 0))
        assertEquals("Connected, no calendars found", SettingsSummaries.calendars(connected = true, selected = 0, total = 0))
    }

    @Test
    fun `budgets are shown in hours and minutes`() {
        assertEquals("2h weekdays, 3h weekends", SettingsSummaries.socialBudget(120, 180))
        assertEquals("1h 30m weekdays, 2h 15m weekends", SettingsSummaries.socialBudget(90, 135))
    }

    @Test
    fun `the assistant line says how much has been spent, or that it is not set up`() {
        assertEquals("£4.20 of £15 this month", SettingsSummaries.assistant(configured = true, spendGbp = 4.2, capGbp = 15.0))
        assertEquals("Not set up yet", SettingsSummaries.assistant(configured = false, spendGbp = 0.0, capGbp = 15.0))
    }

    @Test
    fun `sleep says whether Health Connect is reading`() {
        assertEquals("Health Connect connected", SettingsSummaries.sleep(SleepSource.CONNECTED))
        assertEquals("Needs permission to read sleep", SettingsSummaries.sleep(SleepSource.NEEDS_PERMISSION))
        assertEquals("Health Connect isn't available", SettingsSummaries.sleep(SleepSource.UNAVAILABLE))
        assertEquals("Checking Health Connect", SettingsSummaries.sleep(SleepSource.UNKNOWN))
    }
}
