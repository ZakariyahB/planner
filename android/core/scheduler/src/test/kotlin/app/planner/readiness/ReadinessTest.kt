package app.planner.readiness

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadinessTest {
    private val zone = ZoneId.of("Europe/London")

    private fun at(date: String, time: String): Instant = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun session(date: String, from: String, toDate: String, to: String, asleep: Int? = null): SleepSession {
        val s = at(date, from)
        val e = at(toDate, to)
        return SleepSession(s, e, asleep ?: java.time.Duration.between(s, e).toMinutes().toInt())
    }

    // Shapes of the real Takeout data.
    private val singleBlock = session("2026-10-28", "01:27", "2026-10-28", "05:00") // 3h33m
    private val splitFirst = session("2026-10-28", "00:32", "2026-10-28", "05:14") // 4h42m
    private val splitSecond = session("2026-10-28", "07:04", "2026-10-28", "09:14") // 2h10m
    private val sleptThrough = session("2026-10-27", "23:50", "2026-10-28", "07:20") // 7h30m

    private fun fullNights(from: String, count: Int): List<SleepNight> = (0 until count).map { i ->
        val d = LocalDate.parse(from).plusDays(i.toLong())
        SleepNight(d, listOf(SleepBlock(d.atTime(0, 15).atZone(zone).toInstant(), d.atTime(7, 15).atZone(zone).toInstant(), 420)))
    }

    @Test
    fun `a single 3h33 block is one night with lights-out at 01 27`() {
        val nights = SleepGrouping.group(listOf(singleBlock), zone)
        assertEquals(1, nights.size)
        assertEquals(LocalDate.parse("2026-10-28"), nights[0].date)
        assertEquals(213, nights[0].asleepMin)
        assertEquals(singleBlock.start, nights[0].lightsOut)
    }

    @Test
    fun `waking at Fajr gives two blocks in one night`() {
        val night = SleepGrouping.group(listOf(splitSecond, splitFirst), zone).single()
        assertEquals(2, night.blocks.size)
        assertEquals(282 + 130, night.asleepMin)
        assertEquals(splitFirst.end, night.firstBlockEnd)
        assertEquals(splitSecond.end, night.lastEnd)
    }

    @Test
    fun `a night slept through from before midnight belongs to the next morning`() {
        val night = SleepGrouping.group(listOf(sleptThrough), zone).single()
        assertEquals(LocalDate.parse("2026-10-28"), night.date)
        assertEquals(1, night.blocks.size)
    }

    @Test
    fun `naps and tiny records are ignored and brief wake-ups merge`() {
        val nap = session("2026-10-28", "14:00", "2026-10-28", "14:40")
        val tiny = session("2026-10-28", "02:00", "2026-10-28", "02:10")
        assertTrue(SleepGrouping.group(listOf(nap, tiny), zone).isEmpty())

        val a = session("2026-10-28", "00:30", "2026-10-28", "03:00")
        val b = session("2026-10-28", "03:20", "2026-10-28", "06:00")
        val merged = SleepGrouping.group(listOf(a, b), zone).single()
        assertEquals(1, merged.blocks.size)
        assertEquals(150 + 160, merged.asleepMin)
    }

    @Test
    fun `lights-out delay is measured from 00 15`() {
        assertEquals(0, ReadinessEngine.lightsOutDelayMin(LocalTime.of(0, 15)))
        assertEquals(0, ReadinessEngine.lightsOutDelayMin(LocalTime.of(23, 30)))
        assertEquals(72, ReadinessEngine.lightsOutDelayMin(LocalTime.of(1, 27)))
    }

    @Test
    fun `short single block after a good week is amber`() {
        val history = fullNights("2026-10-22", 6)
        val night = SleepGrouping.group(listOf(singleBlock), zone).single()
        val r = ReadinessEngine.score(ReadinessEngine.inputsFor(night, history + night, doneRatio = null, zone))
        assertEquals(213, r.inputs.asleepMin)
        assertEquals(207, r.inputs.debtMin)
        assertEquals(72, r.inputs.lightsOutDelayMin)
        assertEquals(59, r.score)
        assertEquals(ReadinessBand.AMBER, r.band)
    }

    @Test
    fun `split night with enough total sleep and early lights-out is green`() {
        val history = fullNights("2026-10-22", 6)
        val night = SleepGrouping.group(listOf(splitFirst, splitSecond), zone).single()
        val r = ReadinessEngine.score(ReadinessEngine.inputsFor(night, history + night, doneRatio = null, zone))
        assertEquals(ReadinessBand.GREEN, r.band)
        assertEquals(93, r.score)
    }

    @Test
    fun `sleeping through the night scores green`() {
        val night = SleepGrouping.group(listOf(sleptThrough), zone).single()
        val r = ReadinessEngine.score(ReadinessEngine.inputsFor(night, fullNights("2026-10-22", 6) + night, doneRatio = 1.0, zone))
        assertEquals(100, r.score)
    }

    @Test
    fun `his typical six hours and 01 27 lights-out is amber`() {
        val typical = (0 until 7).map { i ->
            val d = LocalDate.parse("2026-10-22").plusDays(i.toLong())
            SleepNight(d, listOf(SleepBlock(d.atTime(1, 27).atZone(zone).toInstant(), d.atTime(7, 27).atZone(zone).toInstant(), 360)))
        }
        val r = ReadinessEngine.score(ReadinessEngine.inputsFor(typical.last(), typical, doneRatio = null, zone))
        assertEquals(ReadinessBand.AMBER, r.band)
    }

    @Test
    fun `bands split at 70 and 40`() {
        assertEquals(ReadinessBand.GREEN, ReadinessBand.of(70))
        assertEquals(ReadinessBand.AMBER, ReadinessBand.of(69))
        assertEquals(ReadinessBand.AMBER, ReadinessBand.of(40))
        assertEquals(ReadinessBand.RED, ReadinessBand.of(39))
    }

    @Test
    fun `no sleep data for last night means waiting, never a score`() {
        val state = ReadinessEngine.evaluate(at("2026-10-28", "08:00"), zone, LocalTime.of(8, 45), fullNights("2026-10-22", 6), null)
        assertEquals(ReadinessState.Waiting, state)
    }

    @Test
    fun `after the first block at Fajr time it is provisional with back-to-bed advice`() {
        val nights = fullNights("2026-10-22", 6) + SleepGrouping.group(listOf(splitFirst), zone)
        val state = ReadinessEngine.evaluate(at("2026-10-28", "05:30"), zone, LocalTime.of(8, 45), nights, null)
        assertIs<ReadinessState.Provisional>(state)
        assertEquals(282, state.asleepSoFarMin)
        assertEquals(BackToBedAdvice(sleepMin = 138, alarm = LocalTime.of(8, 45)), state.advice)
    }

    @Test
    fun `advice is capped by the time left before the alarm`() {
        assertEquals(BackToBedAdvice(60, LocalTime.of(8, 45)), ReadinessEngine.advice(at("2026-10-28", "07:45"), zone, LocalTime.of(8, 45), asleepSoFar = 100))
        assertNull(ReadinessEngine.advice(at("2026-10-28", "09:00"), zone, LocalTime.of(8, 45), asleepSoFar = 100))
    }

    @Test
    fun `final waits until 30 minutes after the last sleep, or 10 00`() {
        val nights = fullNights("2026-10-22", 6) + SleepGrouping.group(listOf(splitFirst, splitSecond), zone)
        val evaluate = { time: String -> ReadinessEngine.evaluate(at("2026-10-28", time), zone, LocalTime.of(8, 45), nights, null) }
        assertIs<ReadinessState.Provisional>(evaluate("09:30"))
        assertIs<ReadinessState.Final>(evaluate("09:50"))

        val early = fullNights("2026-10-22", 6) + SleepGrouping.group(listOf(session("2026-10-28", "00:30", "2026-10-28", "05:00")), zone)
        assertIs<ReadinessState.Provisional>(ReadinessEngine.evaluate(at("2026-10-28", "06:00"), zone, LocalTime.of(8, 45), early, null))
        assertIs<ReadinessState.Provisional>(ReadinessEngine.evaluate(at("2026-10-28", "09:00"), zone, LocalTime.of(8, 45), early, null))
        assertIs<ReadinessState.Final>(ReadinessEngine.evaluate(at("2026-10-28", "10:00"), zone, LocalTime.of(8, 45), early, null))
        // A confirmed unlock after 07:00 settles it earlier, but not before 07:00.
        assertIs<ReadinessState.Final>(ReadinessEngine.evaluate(at("2026-10-28", "07:30"), zone, LocalTime.of(8, 45), early, null, awakeConfirmed = true))
        assertIs<ReadinessState.Provisional>(ReadinessEngine.evaluate(at("2026-10-28", "06:30"), zone, LocalTime.of(8, 45), early, null, awakeConfirmed = true))
    }

    @Test
    fun `median lights-out is taken across the evening boundary`() {
        val nights = listOf("23:50", "00:30", "01:27").mapIndexed { i, t ->
            val d = LocalDate.parse("2026-10-24").plusDays(i.toLong())
            val start = LocalTime.parse(t).let { lt -> if (lt >= LocalTime.of(18, 0)) d.minusDays(1).atTime(lt) else d.atTime(lt) }
            SleepNight(d, listOf(SleepBlock(start.atZone(zone).toInstant(), start.plusHours(6).atZone(zone).toInstant(), 360)))
        }
        assertEquals(LocalTime.of(0, 30), ReadinessEngine.medianLightsOut(nights, zone))
        assertNull(ReadinessEngine.medianLightsOut(emptyList(), zone))
    }

    @Test
    fun `forecast shows what an on-time lights-out buys against his usual late one`() {
        val history = fullNights("2026-10-22", 6)
        val tonight = LocalDate.parse("2026-10-27")
        val onTime = ReadinessEngine.forecast(LocalTime.of(0, 15), LocalTime.of(8, 45), tonight, history, null)
        val late = ReadinessEngine.forecast(LocalTime.of(1, 27), LocalTime.of(8, 45), tonight, history, null)
        assertEquals(ReadinessBand.GREEN, onTime.band)
        assertTrue(onTime.score > late.score)
        assertEquals(433, onTime.inputs.asleepMin)
    }
}

class SleepPhasesTest {
    private val zone = ZoneId.of("Europe/London")

    private fun block(from: String, to: String, asleep: Int) = SleepBlock(
        LocalDate.parse("2026-10-28").atTime(LocalTime.parse(from)).atZone(zone).toInstant(),
        LocalDate.parse("2026-10-28").atTime(LocalTime.parse(to)).atZone(zone).toInstant(),
        asleep,
    )

    @Test
    fun `a split night reads asleep, awake, asleep`() {
        val night = SleepNight(LocalDate.parse("2026-10-28"), listOf(block("00:32", "05:14", 282), block("07:04", "09:14", 130)))
        assertEquals(
            listOf(
                SleepPhase(SleepPhaseKind.ASLEEP, LocalTime.of(0, 32), LocalTime.of(5, 14)),
                SleepPhase(SleepPhaseKind.AWAKE, LocalTime.of(5, 14), LocalTime.of(7, 4)),
                SleepPhase(SleepPhaseKind.ASLEEP, LocalTime.of(7, 4), LocalTime.of(9, 14)),
            ),
            night.phases(zone),
        )
    }

    @Test
    fun `a single block is one asleep phase`() {
        val night = SleepNight(LocalDate.parse("2026-10-28"), listOf(block("01:27", "05:00", 213)))
        assertEquals(listOf(SleepPhase(SleepPhaseKind.ASLEEP, LocalTime.of(1, 27), LocalTime.of(5, 0))), night.phases(zone))
    }

    @Test
    fun `phases are named for display`() {
        assertEquals("asleep 00:32–05:14", SleepPhase(SleepPhaseKind.ASLEEP, LocalTime.of(0, 32), LocalTime.of(5, 14)).label())
        assertEquals("awake 05:14–07:04", SleepPhase(SleepPhaseKind.AWAKE, LocalTime.of(5, 14), LocalTime.of(7, 4)).label())
    }
}
