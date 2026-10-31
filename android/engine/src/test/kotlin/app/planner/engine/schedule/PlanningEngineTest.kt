package app.planner.engine.schedule

import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Placement
import app.planner.engine.model.Prayer
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.model.TaskKind
import app.planner.engine.model.TaskStatus
import app.planner.engine.model.Tier
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** newplan 5.3 to 5.6 on single days of the reference week (its anchors), one rule per test. */
class PlanningEngineTest {
    private val engine = Engine.planning
    private val mon = ReferenceWeek.days[0]
    private val tue = ReferenceWeek.days[1]
    private val wed = ReferenceWeek.days[2]
    private val fri = ReferenceWeek.days[4]
    private val sat = ReferenceWeek.days[5]
    private val sun = ReferenceWeek.days[6]

    private fun t(s: String): LocalTime = LocalTime.parse(s)
    private fun at(d: LocalDate, s: String): LocalDateTime = d.atTime(t(s))

    private fun task(
        id: String, min: Int, day: LocalDate? = mon, from: String? = null, after: Prayer? = null,
        priority: Int = 0, kind: TaskKind = TaskKind.DEEP, splittable: Boolean = true, deadline: LocalDate? = null,
        status: TaskStatus = TaskStatus.BACKLOG,
    ) = Task(
        id, id, CalendarCategory.ACADEMIC, min, assignedDay = day, earliestStart = from?.let(::t), afterPrayer = after,
        priority = priority, kind = kind, splittable = splittable, deadline = deadline, status = status,
    )

    private fun busy(d: LocalDate, from: String, to: String, title: String = "Busy", assumed: Boolean = false) =
        FixedEvent(EventRef("cal", "$title-$d-$from"), title, at(d, from), at(d, to), assumed = assumed)

    private fun plan(
        tasks: List<Task>, fixed: List<FixedEvent> = emptyList(), days: List<LocalDate> = listOf(mon),
        settings: Settings = Settings(), keep: List<Placement> = emptyList(), notBefore: LocalDateTime? = null,
        catchUp: LocalDate? = null,
    ): PlanResult {
        val request = PlanRequest(days, tasks, fixed, ReferenceWeek.anchors, settings, notBefore, keep, catchUp)
        val result = engine.plan(request)
        val v = PlanInvariants.check(request, result)
        if (v.isNotEmpty()) fail(v.joinToString("\n"))
        return result
    }

    private fun PlanResult.of(id: String) = placements.filter { it.taskId == id }
    private fun PlanResult.times(id: String) = of(id).map { it.start.toLocalTime().toString() + "-" + it.end.toLocalTime() }

    // 5.4 constraints

    @Test fun `earliestStart - a block never starts before it`() {
        val r = plan(listOf(task("lecture", 60, from = "15:30")))
        assertEquals(listOf("15:30-16:30"), r.times("lecture"))
    }

    @Test fun `afterPrayer - a block may not start before that prayer's slot ends`() {
        val r = plan(listOf(task("arabic", 60, after = Prayer.ASR)))
        assertEquals(listOf("14:20-15:20"), r.times("arabic"))
    }

    @Test fun `afterPrayer counts from the prayer even when it is absorbed by a fixed event`() {
        val halaqa = busy(wed, "14:00", "16:30", "Halaqa")
        val r = plan(listOf(task("arabic", 60, day = wed, after = Prayer.ASR)), listOf(halaqa), listOf(wed))
        // Asr 14:02 is absorbed (no slot) but "after Asr" still means after 14:17, so the first free time is 16:30.
        assertEquals(at(wed, "16:30"), r.of("arabic").single().start)
    }

    @Test fun `afterPrayer with no anchor that day - Friday Dhuhr - overflows as unsatisfiable`() {
        val r = plan(listOf(task("x", 60, day = fri, after = Prayer.DHUHR)), days = listOf(fri))
        assertTrue(r.placements.isEmpty())
        assertEquals(listOf(Overflow("x", 60, OverflowReason.CONSTRAINT_UNSATISFIABLE, sun)), r.overflow)
        assertTrue(r.warnings.any { it is PlanWarning.Note && "Dhuhr" in it.text })
    }

    @Test fun `deadline - a plan that misses it warns`() {
        val r = plan(listOf(task("late", 800, deadline = mon, splittable = false)))
        assertTrue(r.warnings.contains(PlanWarning.DeadlineMissed("late", mon, null)))
        val ok = plan(listOf(task("fine", 60, deadline = mon)))
        assertTrue(ok.warnings.none { it is PlanWarning.DeadlineMissed })
    }

    // 5.5 placement order

    @Test fun `most constrained tasks first - a lecture keeps its recording time against a higher-priority task`() {
        val r = plan(listOf(task("deep", 300, priority = 5), task("lecture", 60, from = "11:00", kind = TaskKind.PASSIVE)))
        assertEquals(listOf("11:00-12:15"), r.times("lecture"))
        // The deep task works around it: one block after it fits in tier 0 (12:15 + 5h + Asr + ... is too long), so it splits.
        assertTrue(r.of("deep").none { it.start.isBefore(at(mon, "12:15")) && at(mon, "11:00").isBefore(it.end) })
    }

    @Test fun `constrained tasks are placed by their earliest time, not input order`() {
        val r = plan(listOf(task("arabic", 60, day = tue, after = Prayer.ASR), task("lecture", 60, day = tue, from = "14:00")), days = listOf(tue))
        assertEquals(listOf("14:00-15:15"), r.times("lecture"))
        assertEquals(listOf("15:15-16:15"), r.times("arabic"))
    }

    @Test fun `higher priority first`() {
        val r = plan(listOf(task("low", 60), task("high", 60, priority = 1)))
        assertEquals(listOf("09:30-10:30"), r.times("high"))
        assertEquals(listOf("10:30-11:30"), r.times("low"))
    }

    @Test fun `deep work earlier, passive later`() {
        val r = plan(listOf(task("lecture", 60, kind = TaskKind.PASSIVE), task("sheet", 120)))
        assertEquals(listOf("09:30-11:30"), r.times("sheet"))
        assertEquals(listOf("11:30-12:45"), r.times("lecture"))
    }

    @Test fun `the catch-up block (OVERFLOW kind) goes last, even with a high priority`() {
        val r = plan(listOf(task("catchup", 60, kind = TaskKind.OVERFLOW, priority = 9), task("work", 60)))
        assertEquals(listOf("09:30-10:30"), r.times("work"))
        assertEquals(listOf("10:30-11:30"), r.times("catchup"))
    }

    @Test fun `equal tasks keep the user's order`() {
        val r = plan(listOf(task("a", 60), task("b", 60), task("c", 60)))
        assertEquals(listOf("a", "b", "c"), r.placements.map { it.taskId })
    }

    @Test fun `tier 0 first, then rest, extended and over the soft limit, then overflow`() {
        val full = busy(mon, "09:30", "16:50")
        // Decreasing priorities fix the order (equal ranks would be reordered to minimise overflow).
        val tasks = listOf("a", "b", "c", "d").mapIndexed { i, id -> task(id, if (id == "d") 120 else 60, splittable = false, priority = 3 - i) }
        val r = plan(tasks, listOf(full))
        assertEquals(listOf("16:50-17:50"), r.times("a"))
        assertEquals(listOf("17:50-19:05"), r.times("b")) // covers Isha 18:03
        assertEquals(listOf("19:05-20:05"), r.times("c"))
        assertEquals(listOf(Tier.REST, Tier.EXTENDED, Tier.OVER_SOFT_LIMIT), r.placements.map { it.tier })
        assertEquals(listOf(Overflow("d", 120, OverflowReason.NO_ROOM, sun)), r.overflow)
        val day = r.days.getValue(mon)
        assertEquals(Tier.OVER_SOFT_LIMIT, day.maxTier)
        assertEquals(t("20:05"), day.endTime)
        assertEquals(setOf(DayFlag.USES_REST_TIME, DayFlag.EXTENDED_DAY, DayFlag.OVER_SOFT_LIMIT, DayFlag.HAS_OVERFLOW), day.flags)
    }

    @Test fun `rest time is used only when tier 0 cannot fit`() {
        val r = plan(listOf(task("a", 60), task("b", 60)), listOf(busy(mon, "09:30", "15:35")))
        // a ends exactly at Maghrib 16:35 (not covered); b cannot start in the Maghrib slot, so it starts 16:50.
        assertEquals(listOf("15:35-16:35", "16:50-17:50"), r.placements.map { it.start.toLocalTime().toString() + "-" + it.end.toLocalTime() })
        assertEquals(listOf(Tier.NORMAL, Tier.REST), r.placements.map { it.tier })
        assertEquals(setOf(DayFlag.USES_REST_TIME), r.days.getValue(mon).flags)
    }

    // 5.4 splitting

    @Test fun `prefer unsplit - one block in the rest zone rather than chunks back to back across the boundary`() {
        val r = plan(listOf(task("x", 120)), listOf(busy(mon, "09:30", "15:30")))
        assertEquals(listOf("15:30-17:45"), r.times("x"))
        assertEquals(Tier.REST, r.of("x").single().tier)
    }

    @Test fun `split around a fixed event to stay in tier 0 - chunks titled 1 of 2 and 2 of 2`() {
        val r = plan(listOf(task("x", 180)), listOf(busy(mon, "11:00", "14:30")))
        assertEquals(listOf("09:30-11:00", "14:30-16:00"), r.times("x"))
        assertEquals(listOf("(1/2)", "(2/2)"), r.of("x").map { it.label })
        assertTrue(r.of("x").all { it.tier == Tier.NORMAL })
    }

    @Test fun `unsplittable tasks are never split`() {
        val r = plan(listOf(task("x", 180, splittable = false)), listOf(busy(mon, "11:00", "14:30")))
        assertEquals(listOf("14:30-17:45"), r.times("x"))
    }

    @Test fun `a gap shorter than the minimum chunk is not used`() {
        val r = plan(listOf(task("x", 150)), listOf(busy(mon, "10:10", "14:30")))
        assertEquals(listOf("14:30-17:15"), r.times("x"))
    }

    @Test fun `a chunk leaves a remainder of at least the minimum chunk`() {
        val r = plan(listOf(task("x", 100)), listOf(busy(mon, "10:30", "15:30")))
        assertEquals(listOf("09:30-10:25", "15:30-16:15"), r.times("x"))
        assertEquals(listOf(55, 45), r.of("x").map { PlanInvariants.work(it.start, it.end, it.prayerBufferMin) })
    }

    @Test fun `the minimum chunk comes from settings when larger`() {
        val r = plan(listOf(task("x", 120)), listOf(busy(mon, "10:30", "15:30")), settings = Settings(minChunkMin = 61))
        // 60 before the event is under 61, so it is not used; one block after it instead.
        assertEquals(listOf("15:30-17:45"), r.times("x"))
    }

    @Test fun `each chunk runs the prayer rule independently`() {
        val r = plan(listOf(task("x", 240)), listOf(busy(mon, "12:30", "15:30")))
        assertEquals(listOf("09:30-12:30", "15:30-17:00"), r.times("x"))
        assertEquals(listOf(listOf(Prayer.DHUHR), listOf(Prayer.MAGHRIB)), r.of("x").map { it.coveredPrayers })
        assertEquals(listOf(15, 15), r.of("x").map { it.prayerBufferMin })
        assertEquals(listOf(Tier.NORMAL, Tier.REST), r.of("x").map { it.tier })
    }

    @Test fun `equal-rank tasks are packed to keep the day short - the Wednesday case`() {
        val bol = busy(wed, "10:15", "12:45", "BoL")
        val halaqa = busy(wed, "14:00", "16:30", "Halaqa")
        val r = plan(listOf(task("cw", 90, day = wed), task("ws", 120, day = wed)), listOf(bol, halaqa), listOf(wed))
        // In the user's order, CW would take 45+45 before the Halaqa and the worksheet would end at 19:01. Trying the
        // other order puts the worksheet in the two morning gaps and the CW in one block ending 18:30.
        assertEquals(listOf("09:30-10:15", "12:45-14:00"), r.times("ws"))
        assertEquals(listOf("16:30-18:30"), r.times("cw"))
    }

    // Overflow

    @Test fun `overflow goes to the catch-up day, never the next day`() {
        val r = plan(listOf(task("x", 60, day = tue, splittable = false)), listOf(busy(tue, "09:00", "22:00")), listOf(tue))
        assertEquals(sun, r.overflow.single().proposedDate)
        val custom = plan(listOf(task("x", 60, day = tue, splittable = false)), listOf(busy(tue, "09:00", "22:00")), listOf(tue), settings = Settings(catchUpDay = DayOfWeek.SATURDAY))
        assertEquals(sat, custom.overflow.single().proposedDate)
        val explicit = plan(listOf(task("x", 60, day = tue, splittable = false)), listOf(busy(tue, "09:00", "22:00")), listOf(tue), catchUp = fri)
        assertEquals(fri, explicit.overflow.single().proposedDate)
    }

    @Test fun `overflow on the catch-up day itself has no proposed date`() {
        val r = plan(listOf(task("x", 60, day = sun)), listOf(busy(sun, "09:00", "22:00")), listOf(sun))
        assertNull(r.overflow.single().proposedDate)
    }

    @Test fun `a split task can be partly placed - only the rest overflows`() {
        val r = plan(listOf(task("x", 180)), listOf(busy(mon, "11:00", "21:00")))
        assertEquals(listOf("09:30-11:00"), r.times("x"))
        assertEquals(listOf(Overflow("x", 90, OverflowReason.PAST_HARD_CAP, sun)), r.overflow)
    }

    @Test fun `overflow reasons`() {
        // Room taken by other work.
        val noRoom = plan(listOf(task("a", 600, splittable = false, priority = 1), task("b", 300, splittable = false)))
        assertEquals(OverflowReason.NO_ROOM, noRoom.overflow.single { it.taskId == "b" }.reason)
        // Fits on the day, but not after its earliest start.
        val late = plan(listOf(task("c", 60, from = "21:30")))
        assertEquals(OverflowReason.CONSTRAINT_UNSATISFIABLE, late.overflow.single().reason)
        // Longer than the whole day.
        val long = plan(listOf(task("d", 800, splittable = false)))
        assertEquals(OverflowReason.PAST_HARD_CAP, long.overflow.single().reason)
    }

    // 5.6 replanning

    @Test fun `keep - kept blocks stay where they are, their work counts, the rest is placed around them`() {
        val kept = Placement("x", 1, 1, at(mon, "10:00"), at(mon, "11:00"))
        val r = plan(listOf(task("x", 120), task("y", 30)), keep = listOf(kept))
        assertEquals(listOf("10:00-11:00", "11:00-12:15"), r.times("x"))
        assertEquals(listOf("(1/2)", "(2/2)"), r.of("x").map { it.label })
        assertEquals(listOf("09:30-10:00"), r.times("y"))
    }

    @Test fun `keep - a fully kept task is not placed again`() {
        val kept = Placement("x", 1, 1, at(mon, "13:00"), at(mon, "14:00"))
        val r = plan(listOf(task("x", 60)), keep = listOf(kept))
        assertEquals(listOf(kept), r.placements)
    }

    @Test fun `notBefore - nothing new starts before now, and earlier days get nothing`() {
        val now = at(tue, "13:00")
        val r = plan(listOf(task("m", 60), task("t", 60, day = tue)), days = listOf(mon, tue), notBefore = now)
        assertEquals(listOf(Overflow("m", 60, OverflowReason.PAST_HARD_CAP, sun)), r.overflow)
        assertEquals(listOf("13:00-14:00"), r.times("t"))
        val late = plan(listOf(task("t", 60, day = tue)), days = listOf(tue), notBefore = at(tue, "22:30"))
        assertEquals(OverflowReason.PAST_HARD_CAP, late.overflow.single().reason)
    }

    @Test fun `notBefore inside a prayer slot moves to its end`() {
        val r = plan(listOf(task("t", 60, day = tue)), days = listOf(tue), notBefore = at(tue, "14:10"))
        assertEquals(listOf("14:19-15:19"), r.times("t"))
    }

    // Other request shapes

    @Test fun `a task with no day goes on the day where it fits in the lowest tier`() {
        val r = plan(listOf(task("free", 60, day = null)), listOf(busy(mon, "09:00", "16:50")), listOf(mon, tue))
        assertEquals(listOf(at(tue, "09:30")), r.of("free").map { it.start })
        val none = plan(listOf(task("free", 60, day = null)), listOf(busy(mon, "09:00", "22:00")), listOf(mon))
        assertEquals(listOf(Overflow("free", 60, OverflowReason.PAST_HARD_CAP, sun)), none.overflow)
    }

    @Test fun `done, skipped and other days' tasks are left alone`() {
        val r = plan(listOf(task("done", 60, status = TaskStatus.DONE), task("skip", 60, status = TaskStatus.SKIPPED), task("other", 60, day = tue)))
        assertTrue(r.placements.isEmpty())
        assertTrue(r.overflow.isEmpty())
    }

    @Test fun `assumed fixed events are planned around and reported`() {
        val halaqa = busy(wed, "14:00", "16:30", "Halaqa", assumed = true)
        val r = plan(listOf(task("x", 360, day = wed)), listOf(halaqa), listOf(wed))
        assertTrue(r.placements.none { it.start.isBefore(halaqa.end) && halaqa.start.isBefore(it.end) })
        assertEquals(listOf<PlanWarning>(PlanWarning.AssumedGapKept(wed, "Halaqa")), r.warnings.filterIsInstance<PlanWarning.AssumedGapKept>())
    }

    @Test fun `a day with no prayer times is planned without buffers and noted`() {
        val nextMon = mon.plusDays(7)
        val r = engine.plan(PlanRequest(listOf(nextMon), listOf(task("x", 180, day = nextMon)), emptyList(), ReferenceWeek.anchors, Settings()))
        assertEquals(0, r.placements.single().prayerBufferMin)
        assertTrue(r.warnings.any { it is PlanWarning.Note && "No prayer times" in it.text })
    }

    @Test fun `weekend placements carry the interpolated flag`() {
        val r = plan(listOf(task("x", 120, day = sat)), days = listOf(sat), settings = Settings(wakeTime = t("11:00")))
        assertTrue(r.of("x").single().interpolatedPrayer)
        assertTrue(DayFlag.INTERPOLATED_PRAYERS in r.days.getValue(sat).flags)
        assertTrue(DayFlag.INTERPOLATED_PRAYERS !in plan(listOf(task("y", 60))).days.getValue(mon).flags)
    }

    @Test fun `no days means an empty plan`() {
        val r = engine.plan(PlanRequest(emptyList(), listOf(task("x", 60)), emptyList(), ReferenceWeek.anchors, Settings()))
        assertEquals(PlanResult(emptyList(), emptyList(), emptyList(), emptyMap()), r)
    }
}
