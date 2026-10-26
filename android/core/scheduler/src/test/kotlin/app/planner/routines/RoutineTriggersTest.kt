package app.planner.routines

import app.planner.readiness.SleepBlock
import app.planner.readiness.SleepNight
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineTriggersTest {
    private val zone = ZoneId.of("Europe/London")
    private val wake = LocalTime.of(8, 45)
    private val fajr = LocalTime.of(5, 30)
    private val today = LocalDate.parse("2026-10-28")

    private fun at(time: String, date: LocalDate = today): Instant = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun block(from: String, to: String, asleepMin: Int? = null): SleepBlock {
        val s = at(from)
        val e = at(to)
        return SleepBlock(s, e, asleepMin ?: java.time.Duration.between(s, e).toMinutes().toInt())
    }

    private fun night(vararg blocks: SleepBlock, date: LocalDate = today) = SleepNight(date, blocks.toList())

    private fun provisionalDue(
        night: SleepNight?,
        now: String,
        fajr: LocalTime? = this.fajr,
        sentOn: LocalDate? = null,
    ) = RoutineTriggers.provisionalDue(night, at(now), zone, fajr, wake, sentOn)

    private val firstBlock = night(block("00:32", "05:14"))

    @Test
    fun `provisional is due when the first block ended near Fajr and he may go back to bed`() {
        assertTrue(provisionalDue(firstBlock, now = "05:20"))
    }

    @Test
    fun `provisional is sent once a day`() {
        assertFalse(provisionalDue(firstBlock, now = "05:20", sentOn = today))
        assertTrue(provisionalDue(firstBlock, now = "05:20", sentOn = today.minusDays(1)))
    }

    @Test
    fun `provisional is not due when a second block already exists`() {
        assertFalse(provisionalDue(night(block("00:32", "05:14"), block("05:40", "07:04")), now = "07:10"))
    }

    @Test
    fun `provisional is not due when the block ended well after Fajr`() {
        assertFalse(provisionalDue(night(block("02:00", "08:00")), now = "08:05"))
    }

    @Test
    fun `provisional is not due when the block ended long before Fajr`() {
        assertFalse(provisionalDue(night(block("00:10", "02:30")), now = "02:40"))
    }

    @Test
    fun `provisional is stale after an hour and a half`() {
        assertTrue(provisionalDue(firstBlock, now = "06:40"))
        assertFalse(provisionalDue(firstBlock, now = "06:45"))
    }

    @Test
    fun `provisional is not due once the alarm time has passed`() {
        assertFalse(provisionalDue(night(block("03:00", "07:30")), now = "08:50"))
    }

    @Test
    fun `provisional is not due with no sleep on record or only an old night`() {
        assertFalse(provisionalDue(null, now = "05:20"))
        assertFalse(provisionalDue(night(block("00:32", "05:14"), date = today.minusDays(1)), now = "05:20"))
    }

    @Test
    fun `provisional is not due when he has already had enough sleep`() {
        assertFalse(provisionalDue(night(block("00:32", "05:14", asleepMin = 440)), now = "05:20"))
    }

    @Test
    fun `provisional is not due before the block has ended`() {
        assertFalse(provisionalDue(firstBlock, now = "05:00"))
    }

    @Test
    fun `without a Fajr time a wide morning window is used`() {
        assertTrue(provisionalDue(night(block("00:30", "05:00")), now = "05:10", fajr = null))
        assertFalse(provisionalDue(night(block("00:30", "10:00")), now = "10:05", fajr = null))
        assertFalse(provisionalDue(night(block("00:30", "03:00")), now = "03:05", fajr = null))
    }

    // Morning brief

    private fun brief(now: String, awake: Boolean, sentOn: LocalDate? = null) =
        RoutineTriggers.morningBriefDue(at(now), zone, sentOn, awake)

    @Test
    fun `morning brief waits for 07 00 even if he is up`() {
        assertFalse(brief("06:59", awake = true))
        assertTrue(brief("07:00", awake = true))
    }

    @Test
    fun `morning brief needs the phone unlocked until the 10 00 fallback`() {
        assertFalse(brief("09:59", awake = false))
        assertTrue(brief("10:00", awake = false))
    }

    @Test
    fun `morning brief is sent once a day`() {
        assertFalse(brief("08:00", awake = true, sentOn = today))
        assertTrue(brief("08:00", awake = true, sentOn = today.minusDays(1)))
        assertFalse(brief("11:00", awake = false, sentOn = today))
    }

    @Test
    fun `morning brief still goes out late in the morning if it was missed but not in the afternoon`() {
        assertTrue(brief("13:59", awake = false))
        assertFalse(brief("14:00", awake = false))
        assertFalse(brief("15:00", awake = true))
    }

    @Test
    fun `the default windows are the ones the plan promises`() {
        assertEquals(LocalTime.of(7, 0), RoutineTriggers.MORNING_FROM)
        assertEquals(LocalTime.of(10, 0), RoutineTriggers.MORNING_FALLBACK)
    }
}
