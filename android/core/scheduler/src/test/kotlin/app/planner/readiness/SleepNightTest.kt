package app.planner.readiness

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a night is made of: when he slept, when he woke, when he was up in between, and the total. */
class SleepNightTest {
    private val zone = ZoneId.of("Europe/London")

    private fun at(date: String, time: String): Instant = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun stage(kind: StageKind, from: String, to: String, date: String = "2026-10-27") =
        SleepStage(kind, at(date, from), at(date, to))

    @Test
    fun `the total is time in bed minus the time awake`() {
        val session = sleepSessionFromStages(
            at("2026-10-27", "00:20"), at("2026-10-27", "09:41"),
            listOf(
                stage(StageKind.LIGHT, "00:20", "03:00"),
                stage(StageKind.AWAKE, "03:00", "03:25"),
                stage(StageKind.DEEP, "03:25", "05:00"),
                stage(StageKind.AWAKE, "05:00", "05:40"),
                stage(StageKind.REM, "05:40", "09:41"),
            ),
        )
        assertEquals(9 * 60 + 21 - 65, session.asleepMin)
        assertEquals(2, session.awake.size)
        assertEquals(25, session.awake[0].minutes)
        assertEquals(40, session.awake[1].minutes)
    }

    @Test
    fun `a stage with an unfamiliar label still counts as sleep`() {
        val session = sleepSessionFromStages(
            at("2026-10-27", "00:00"), at("2026-10-27", "08:00"),
            listOf(stage(StageKind.UNKNOWN, "00:00", "06:00"), stage(StageKind.AWAKE, "06:00", "06:30"), stage(StageKind.LIGHT, "06:30", "08:00")),
        )
        assertEquals(8 * 60 - 30, session.asleepMin)
    }

    @Test
    fun `short stages are summed in seconds before rounding`() {
        // 600 sleeping stages of 90 seconds: rounding each to the minute would lose a third of the night.
        val start = at("2026-10-27", "00:00")
        val stages = (0 until 600).map { i ->
            SleepStage(StageKind.LIGHT, start.plusSeconds(i * 90L), start.plusSeconds((i + 1) * 90L))
        }
        val session = sleepSessionFromStages(start, start.plusSeconds(600 * 90L), stages)
        assertEquals(900, session.asleepMin)
    }

    @Test
    fun `half a minute of stirring is not an interruption`() {
        val start = at("2026-10-27", "00:00")
        val stages = listOf(
            SleepStage(StageKind.LIGHT, start, start.plusSeconds(3600)),
            SleepStage(StageKind.AWAKE, start.plusSeconds(3600), start.plusSeconds(3630)),
            SleepStage(StageKind.LIGHT, start.plusSeconds(3630), start.plusSeconds(7200)),
        )
        val session = sleepSessionFromStages(start, start.plusSeconds(7200), stages)
        assertTrue(session.awake.isEmpty())
        assertEquals(120, session.asleepMin)
    }

    @Test
    fun `overlapping awake stages are counted once and stages outside the record are ignored`() {
        val session = sleepSessionFromStages(
            at("2026-10-27", "00:00"), at("2026-10-27", "04:00"),
            listOf(
                stage(StageKind.AWAKE, "01:00", "01:30"),
                stage(StageKind.AWAKE, "01:20", "01:50"),
                stage(StageKind.AWAKE, "03:50", "05:00"),
            ),
        )
        assertEquals(240 - 50 - 10, session.asleepMin)
        assertEquals(listOf(50, 10), session.awake.map { it.minutes })
    }

    @Test
    fun `a record without stages is all asleep`() {
        val session = sleepSessionFromStages(at("2026-10-27", "00:00"), at("2026-10-27", "07:00"), emptyList())
        assertEquals(420, session.asleepMin)
        assertTrue(session.awake.isEmpty())
    }

    @Test
    fun `deep and rem minutes come from their stages`() {
        val session = sleepSessionFromStages(
            at("2026-10-27", "00:00"), at("2026-10-27", "03:00"),
            listOf(stage(StageKind.LIGHT, "00:00", "01:00"), stage(StageKind.DEEP, "01:00", "02:00"), stage(StageKind.REM, "02:00", "03:00")),
        )
        assertEquals(60, session.deepMin)
        assertEquals(60, session.remMin)
    }

    // A night built from one or more records

    @Test
    fun `a night lists interruptions inside the record and the gap when he got up at Fajr`() {
        val first = sleepSessionFromStages(
            at("2026-10-27", "00:20"), at("2026-10-27", "05:14"),
            listOf(stage(StageKind.LIGHT, "00:20", "02:00"), stage(StageKind.AWAKE, "02:00", "02:20"), stage(StageKind.LIGHT, "02:20", "05:14")),
        )
        val second = SleepSession(at("2026-10-27", "07:04"), at("2026-10-27", "09:41"), 157)
        val night = SleepGrouping.group(listOf(second, first), zone).single()

        assertEquals(2, night.blocks.size)
        assertEquals(at("2026-10-27", "00:20"), night.lightsOut)
        assertEquals(at("2026-10-27", "09:41"), night.lastEnd)
        assertEquals(listOf("02:00–02:20 (20m)", "05:14–07:04 (1h 50m)"), night.interruptionLabels(zone))
        assertEquals(294 - 20 + 157, night.asleepMin)
        assertEquals(9 * 60 + 21, night.inBedMin)
        assertEquals(130, night.awakeMin)
    }

    @Test
    fun `a short wake-up between two records is merged but still shown as an interruption`() {
        val a = SleepSession(at("2026-10-27", "00:00"), at("2026-10-27", "04:00"), 240)
        val b = SleepSession(at("2026-10-27", "04:12"), at("2026-10-27", "08:00"), 228)
        val night = SleepGrouping.group(listOf(a, b), zone).single()
        assertEquals(1, night.blocks.size)
        assertEquals(468, night.asleepMin)
        assertEquals(listOf("04:00–04:12 (12m)"), night.interruptionLabels(zone))
    }

    @Test
    fun `two apps recording the same sleep are not added together`() {
        val fitbit = SleepSession(at("2026-10-27", "00:20"), at("2026-10-27", "08:20"), 440)
        val phone = SleepSession(at("2026-10-27", "00:30"), at("2026-10-27", "08:10"), 460)
        val night = SleepGrouping.group(listOf(fitbit, phone), zone).single()
        // the overlap (460 minutes of the phone's record) is already in the first record
        assertTrue(night.asleepMin <= Duration.between(night.lightsOut, night.lastEnd).toMinutes())
        assertEquals(440, night.asleepMin)
    }

    @Test
    fun `an unbroken night has no interruptions`() {
        val night = SleepGrouping.group(listOf(SleepSession(at("2026-10-27", "23:50"), at("2026-10-28", "07:20"), 450)), zone).single()
        assertTrue(night.interruptions.isEmpty())
        assertEquals("23:50 to 07:20 · slept 7h 30m of 7h 30m in bed · no interruptions", night.summaryLine(zone))
    }

    @Test
    fun `the summary line gives times, total and interruptions`() {
        val s = sleepSessionFromStages(
            at("2026-10-27", "00:20"), at("2026-10-27", "09:41"),
            listOf(stage(StageKind.LIGHT, "00:20", "03:00"), stage(StageKind.AWAKE, "03:00", "04:05"), stage(StageKind.LIGHT, "04:05", "09:41")),
        )
        val night = SleepGrouping.group(listOf(s), zone).single()
        assertEquals("00:20 to 09:41 · slept 8h 16m of 9h 21m in bed · 1 interruption, 1h 5m awake", night.summaryLine(zone))
    }
}

class SleepCorrectionTest {
    @Test
    fun `a score is redone when the night's sleep changed by ten minutes or more`() {
        assertEquals(true, ReadinessEngine.sleepCorrected(storedAsleepMin = 309, nowAsleepMin = 496))
        assertEquals(true, ReadinessEngine.sleepCorrected(storedAsleepMin = 400, nowAsleepMin = 390))
    }

    @Test
    fun `small differences and no sleep on record leave the score alone`() {
        assertEquals(false, ReadinessEngine.sleepCorrected(storedAsleepMin = 400, nowAsleepMin = 405))
        assertEquals(false, ReadinessEngine.sleepCorrected(storedAsleepMin = 400, nowAsleepMin = null))
    }
}
