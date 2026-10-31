package app.planner.engine.schedule

import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.model.TaskKind
import app.planner.engine.prayer.DefaultPrayerModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * newplan 13.2: property tests over generated days. Every case is replayable from its seed (printed on failure).
 * Times are on a one-minute grid, so anchors, fixed events and constraints collide in awkward ways.
 */
class PlanPropertyTest {
    private val engine = Engine.planning

    private fun time(minuteOfDay: Int): LocalTime = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)

    private fun anchorsFor(r: SeededRandom, date: LocalDate, interpolated: Boolean): List<PrayerAnchor> {
        // Roughly the London year: Dhuhr ~12:00, Asr 13:50-17:30, Maghrib 15:50-21:20, Isha 17:30-23:00 (capped).
        val dhuhr = r.int(11 * 60 + 40, 13 * 60 + 20)
        val asr = dhuhr + r.int(80, 270)
        val maghrib = asr + r.int(90, 240)
        val isha = minOf(maghrib + r.int(60, 120), 23 * 60 + 30)
        return listOf(Prayer.DHUHR to dhuhr, Prayer.ASR to asr, Prayer.MAGHRIB to maghrib, Prayer.ISHA to isha)
            .filter { (p, _) -> DefaultPrayerModel.hasAnchor(date, p) }
            .map { (p, m) -> PrayerAnchor(date, p, time(minOf(m, 23 * 60 + 59)), interpolated) }
    }

    private fun settings(r: SeededRandom): Settings = if (r.chance(70)) Settings() else Settings(
        wakeTime = time(r.int(7 * 60, 11 * 60)),
        normalEnd = time(r.int(16 * 60, 19 * 60)),
        extendedEnd = time(r.int(19 * 60, 21 * 60)),
        hardCap = time(r.int(21 * 60, 23 * 60)),
        prayerSlotMin = r.pick(listOf(10, 15, 15, 20)),
        minChunkMin = r.pick(listOf(30, 45, 45, 60)),
        catchUpDay = r.pick(DayOfWeek.entries),
    )

    private fun fixedFor(r: SeededRandom, date: LocalDate, n: Int): List<FixedEvent> = (0 until n).map { i ->
        val start = r.int(7 * 60, 21 * 60)
        val len = r.int(10, 200)
        FixedEvent(
            EventRef("cal-fixed", "f-$date-$i"), "Fixed $i",
            date.atTime(time(start)), date.atTime(time(minOf(start + len, 23 * 60 + 59))),
            organiserIsExternal = r.chance(20), assumed = r.chance(20),
        )
    }

    private fun tasksFor(r: SeededRandom, date: LocalDate?, n: Int, prefix: String): List<Task> = (0 until n).map { i ->
        Task(
            id = "$prefix-$i",
            title = "Task $prefix-$i",
            category = r.pick(CalendarCategory.entries),
            durationMin = r.pick(listOf(15, 30, 45, 60, 75, 90, 120, 135, 150, 180, 240, 300)) + (if (r.chance(20)) r.int(1, 15) else 0),
            assignedDay = date,
            earliestStart = if (r.chance(30)) time(r.int(9 * 60, 20 * 60)) else null,
            afterPrayer = if (r.chance(20)) r.pick(listOf(Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)) else null,
            deadline = if (r.chance(15)) date?.plusDays(r.int(-1, 3).toLong()) else null,
            splittable = r.chance(80),
            minChunkMin = if (r.chance(80)) 45 else r.int(20, 70),
            priority = r.int(0, 3),
            kind = r.pick(listOf(TaskKind.DEEP, TaskKind.DEEP, TaskKind.PASSIVE, TaskKind.OVERFLOW)),
        )
    }

    /** One to three consecutive days, each with anchors, fixed events and tasks; sometimes a few unassigned tasks. */
    private fun generate(seed: Long): PlanRequest {
        val r = SeededRandom(seed)
        val first = LocalDate.of(2026, 1, 1).plusDays(r.int(0, 365).toLong())
        val days = (0 until r.pick(listOf(1, 1, 1, 2, 3))).map { first.plusDays(it.toLong()) }
        val anchors = days.flatMap { d -> if (r.chance(5)) emptyList() else anchorsFor(r, d, d.dayOfWeek.value >= 6) }
        val fixed = days.flatMap { d -> fixedFor(r, d, r.int(0, 5)) }
        val tasks = days.flatMapIndexed { i, d -> tasksFor(r, d, r.int(1, 8), "d$i") } +
            (if (r.chance(20)) tasksFor(r, null, r.int(1, 3), "u") else emptyList())
        return PlanRequest(days, tasks, fixed, anchors, settings(r))
    }

    private fun assertInvariants(seed: Long, request: PlanRequest, result: PlanResult) {
        val v = PlanInvariants.check(request, result)
        if (v.isNotEmpty()) fail("seed $seed:\n" + v.joinToString("\n") + "\nrequest: $request")
    }

    @Test fun `generated days satisfy every invariant`() {
        for (seed in 1L..2000L) {
            val request = generate(seed)
            assertInvariants(seed, request, engine.plan(request))
        }
    }

    @Test fun `output is deterministic for identical input`() {
        for (seed in 10_000L..10_300L) {
            val request = generate(seed)
            val a = engine.plan(request)
            val b = DefaultPlanningEngine().plan(generate(seed))
            assertEquals(a, b, "seed $seed")
        }
    }

    @Test fun `replanning around kept blocks from now satisfies every invariant`() {
        for (seed in 20_000L..21_000L) {
            val r = SeededRandom(seed * 31)
            val first = generate(seed)
            val plan = engine.plan(first)
            if (plan.placements.isEmpty()) continue
            // Keep a random subset (e.g. started blocks); replan from a random "now" on the first day.
            val keep = plan.placements.filter { r.chance(40) }
            val day = first.days.first()
            val now = day.atTime(time(r.int(8 * 60, 22 * 60)))
            val replan = first.copy(keep = keep, notBefore = now)
            val result = engine.plan(replan)
            assertInvariants(seed, replan, result)
            keep.forEach { k ->
                assertTrue(result.placements.any { it.taskId == k.taskId && it.start == k.start && it.end == k.end }, "seed $seed: kept $k moved")
            }
        }
    }

    @Test fun `placeBlock - fixed point, no start in a slot, anchor at end excluded`() {
        for (seed in 30_000L..32_000L) {
            val r = SeededRandom(seed)
            val date = LocalDate.of(2026, 11, 2).plusDays(r.int(0, 7).toLong())
            val anchors = anchorsFor(r, date, false)
            val slot = r.pick(listOf(0, 10, 15, 20))
            val desired = date.atTime(time(r.int(8 * 60, 21 * 60)))
            val dur = r.int(0, 400)
            val fit = assertIs<BlockFit.Fits>(DefaultBlockPlacer.place(desired, dur, anchors, emptyList(), slot), "seed $seed")
            val times = anchors.map { it.date.atTime(it.begin) }
            assertTrue(!fit.start.isBefore(desired), "seed $seed: start moved earlier")
            if (slot > 0) assertTrue(times.none { !fit.start.isBefore(it) && fit.start.isBefore(it.plusMinutes(slot.toLong())) }, "seed $seed: starts in a slot")
            val n = times.count { !it.isBefore(fit.start) && it.isBefore(fit.end) }
            assertEquals(fit.start.plusMinutes(dur.toLong() + slot.toLong() * n), fit.end, "seed $seed: not a fixed point")
            assertEquals(slot * n, fit.bufferMin, "seed $seed")
            // Starting later than the moved start (if moved) is only because of a slot.
            if (fit.start != desired) assertTrue(times.any { !desired.isBefore(it) && !fit.start.isAfter(it.plusMinutes(slot.toLong())) }, "seed $seed")
        }
    }

    @Test fun `placeBlock collides exactly when the block overlaps a fixed event`() {
        for (seed in 50_000L..51_000L) {
            val r = SeededRandom(seed)
            val date = LocalDate.of(2026, 11, 2).plusDays(r.int(0, 7).toLong())
            val anchors = anchorsFor(r, date, false)
            val fixed = fixedFor(r, date, r.int(1, 4))
            val desired = date.atTime(time(r.int(8 * 60, 21 * 60)))
            val dur = r.int(1, 300)
            val free = assertIs<BlockFit.Fits>(DefaultBlockPlacer.place(desired, dur, anchors, emptyList()))
            val overlaps = fixed.filter { it.start.isBefore(free.end) && free.start.isBefore(it.end) }
            when (val r2 = DefaultBlockPlacer.place(desired, dur, anchors, fixed)) {
                is BlockFit.Fits -> assertTrue(overlaps.isEmpty(), "seed $seed")
                is BlockFit.Collides -> {
                    assertEquals(overlaps.minWith(compareBy({ it.start }, { it.end }, { it.ref.eventId })), r2.with, "seed $seed")
                    assertEquals(free.start to free.end, r2.start to r2.end, "seed $seed")
                }
            }
        }
    }

    @Test fun `the generator is deterministic`() {
        assertEquals(generate(42), generate(42))
        val a = SeededRandom(7)
        val b = SeededRandom(7)
        repeat(100) { assertEquals(a.nextLong(), b.nextLong()) }
        assertTrue((1..1000).map { SeededRandom(it.toLong()).int(0, 10) }.toSet() == (0 until 10).toSet())
    }

    @Suppress("unused")
    private fun at(date: LocalDate, m: Int): LocalDateTime = date.atTime(time(m))
}
