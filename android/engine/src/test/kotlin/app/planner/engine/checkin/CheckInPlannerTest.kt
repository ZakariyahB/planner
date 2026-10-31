package app.planner.engine.checkin

import app.planner.engine.checkin.CheckInKind.END
import app.planner.engine.checkin.CheckInKind.NEXT_UP
import app.planner.engine.checkin.CheckInKind.PRAYER_BREAK
import app.planner.engine.checkin.CheckInKind.START
import app.planner.engine.fakes.FakePlannerQueries
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.EventRef
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.NotificationPrefs
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.Tier
import app.planner.engine.ports.TodayBlock
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CheckInPlannerTest {
    private val settings = Settings()
    private val monday: LocalDate = ReferenceWeek.monday
    private val queries = FakePlannerQueries(now = { monday.atTime(8, 0) })

    private fun day(i: Int): List<TodayBlock> = runBlocking { queries.week(monday).days[i].blocks }

    private fun plan(i: Int, s: Settings = settings): List<CheckIn> =
        CheckInPlanner.plan(ReferenceWeek.days[i], day(i), ReferenceWeek.anchorsOn(ReferenceWeek.days[i]), s)

    /** "09:30 START P&S problem sheet" lines, so a whole day reads as one readable expectation. */
    private fun List<CheckIn>.lines(): List<String> = map { c ->
        val who = when (c.kind) {
            NEXT_UP -> "${c.block.title} -> ${c.next!!.title}"
            PRAYER_BREAK -> "${c.prayer} in ${c.block.title}"
            else -> c.block.title
        }
        "${CheckInTexts.hm(c.at.toLocalTime())} ${c.kind} $who"
    }

    // ---- Full fake days: exactly the expected pings ----

    @Test
    fun `Monday of the reference week`() {
        assertEquals(
            listOf(
                "09:30 START P&S problem sheet",
                "11:30 NEXT_UP P&S problem sheet -> Lecture - CP2",
                "11:49 PRAYER_BREAK DHUHR in Lecture - CP2",
                "12:45 END Lecture - CP2",
                "14:00 START Lecture - P&S",
                "14:05 PRAYER_BREAK ASR in Lecture - P&S",
                "15:15 END Lecture - P&S",
                "17:00 START Lecture - ADA",
                "18:03 PRAYER_BREAK ISHA in Lecture - ADA",
                "19:15 END Lecture - ADA",
            ),
            plan(0).lines(),
        )
    }

    @Test
    fun `Wednesday splits around BoL and the assumed Halaqa`() {
        // Dhuhr is absorbed by BoL and Asr by the Halaqa: no slots, no breaks. Maghrib 16:31 slot ends 16:46.
        assertEquals(
            listOf(
                "09:30 START P&S CW (1/2)",
                "10:15 END P&S CW (1/2)",
                "12:45 START OS worksheet (1/2)",
                "14:00 END OS worksheet (1/2)",
                "16:46 START P&S CW (2/2)",
                "17:31 NEXT_UP P&S CW (2/2) -> OS worksheet (2/2)",
                "18:00 PRAYER_BREAK ISHA in OS worksheet (2/2)",
                "18:31 END OS worksheet (2/2)",
            ),
            plan(2).lines(),
        )
    }

    @Test
    fun `Friday has no Dhuhr break and merges both back-to-back pairs`() {
        assertEquals(
            listOf(
                "09:30 START ADA worksheet",
                "11:00 NEXT_UP ADA worksheet -> PintOS (1/2)",
                "12:00 END PintOS (1/2)",
                "14:15 START PintOS (2/2)",
                "15:15 NEXT_UP PintOS (2/2) -> Lecture - P&S",
                "16:28 PRAYER_BREAK MAGHRIB in Lecture - P&S",
                "17:30 END Lecture - P&S",
            ),
            plan(4).lines(),
        )
    }

    @Test
    fun `Sunday chains three blocks into Next up pings`() {
        assertEquals(
            listOf(
                "09:30 START PintOS (1/2)",
                "11:30 END PintOS (1/2)",
                "13:15 START PintOS (2/2)",
                "13:57 PRAYER_BREAK ASR in PintOS (2/2)",
                "14:30 NEXT_UP PintOS (2/2) -> Internship apps",
                "16:25 PRAYER_BREAK MAGHRIB in Internship apps",
                "16:45 NEXT_UP Internship apps -> Catch-up + plan next week",
                "17:56 PRAYER_BREAK ISHA in Catch-up + plan next week",
                "19:00 END Catch-up + plan next week",
            ),
            plan(6).lines(),
        )
    }

    @Test
    fun `fixed events never get pings`() {
        val all = (0..6).flatMap { plan(it) }
        val fixedRefs = ReferenceWeek.fixed.map { it.ref }.toSet()
        assertTrue(all.none { it.block.ref in fixedRefs || it.next?.ref in fixedRefs })
    }

    @Test
    fun `every ping of the week has a unique id`() {
        val all = (0..6).flatMap { plan(it) }
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    // ---- Rules one at a time, on hand-made days ----

    private val date = LocalDate.of(2026, 11, 2) // Monday: Dhuhr 11:49, Asr 14:05, Maghrib 16:35, Isha 18:03
    private val anchors = ReferenceWeek.anchorsOn(date)

    private fun block(
        id: String, from: String, to: String, buffer: Int = 0, covered: List<Prayer> = emptyList(),
        fixed: Boolean = false, outcome: ExecutionOutcome? = null, ref: Boolean = true,
    ): TodayBlock {
        val start = date.atTime(LocalTime.parse(from))
        val end = date.atTime(LocalTime.parse(to))
        val r = EventRef("cal", id)
        return TodayBlock(
            ref = if (ref) r else null, taskId = if (fixed) null else id, title = id, calendarName = "Academic", category = null,
            start = start, end = end, tier = Tier.NORMAL, prayerBufferMin = buffer, coveredPrayers = covered, isFixed = fixed,
            execution = outcome?.let { ExecutionLog("log-$id", id, r, id, start, end, outcome = it) },
        )
    }

    private fun run(vararg blocks: TodayBlock, prefs: NotificationPrefs = NotificationPrefs(), a: List<PrayerAnchor> = anchors) =
        CheckInPlanner.plan(date, blocks.toList(), a, settings.copy(notifications = prefs)).lines()

    @Test
    fun `a plain block gets a start and an end ping`() {
        assertEquals(listOf("09:30 START A", "11:30 END A"), run(block("A", "09:30", "11:30")))
    }

    @Test
    fun `blocks under 45 minutes of work get a start ping only`() {
        assertEquals(listOf("09:30 START A"), run(block("A", "09:30", "10:14")))
        assertEquals(listOf("09:30 START A", "10:15 END A"), run(block("A", "09:30", "10:15")))
    }

    @Test
    fun `the prayer buffer does not count towards the 45 minutes`() {
        // 30 min of work plus 15 for Dhuhr: start only, and so no prayer break either.
        assertEquals(listOf("11:30 START A"), run(block("A", "11:30", "12:15", buffer = 15, covered = listOf(Prayer.DHUHR))))
    }

    @Test
    fun `the start-only threshold is a setting`() {
        assertEquals(listOf("09:30 START A", "10:00 END A"), run(block("A", "09:30", "10:00"), prefs = NotificationPrefs(startOnlyUnderMin = 30)))
    }

    @Test
    fun `back-to-back blocks get one Next up ping`() {
        assertEquals(
            listOf("09:30 START A", "10:30 NEXT_UP A -> B", "11:30 END B"),
            run(block("A", "09:30", "10:30"), block("B", "10:30", "11:30")),
        )
    }

    @Test
    fun `a gap of up to 5 minutes is still back to back, 6 is not`() {
        assertEquals(
            listOf("09:30 START A", "10:30 NEXT_UP A -> B", "11:35 END B"),
            run(block("A", "09:30", "10:30"), block("B", "10:35", "11:35")),
        )
        assertEquals(
            listOf("09:30 START A", "10:30 END A", "10:36 START B", "11:36 END B"),
            run(block("A", "09:30", "10:30"), block("B", "10:36", "11:36")),
        )
    }

    @Test
    fun `merging can be switched off`() {
        assertEquals(
            listOf("09:30 START A", "10:30 END A", "10:30 START B", "11:30 END B"),
            run(block("A", "09:30", "10:30"), block("B", "10:30", "11:30"), prefs = NotificationPrefs(mergeBackToBack = false)),
        )
    }

    @Test
    fun `a short block before another keeps its start ping and the next one keeps its own`() {
        assertEquals(
            listOf("09:30 START A", "10:00 START B", "11:00 END B"),
            run(block("A", "09:30", "10:00"), block("B", "10:00", "11:00")),
        )
    }

    @Test
    fun `a short block after a long one is merged into Next up`() {
        assertEquals(
            listOf("09:30 START A", "10:30 NEXT_UP A -> B"),
            run(block("A", "09:30", "10:30"), block("B", "10:30", "11:00")),
        )
    }

    @Test
    fun `a block spanning a prayer gets one break ping at the prayer time`() {
        assertEquals(
            listOf("11:30 START A", "11:49 PRAYER_BREAK DHUHR in A", "12:45 END A"),
            run(block("A", "11:30", "12:45", buffer = 15, covered = listOf(Prayer.DHUHR))),
        )
    }

    @Test
    fun `a cascade over two prayers gets a break for each`() {
        val sat = ReferenceWeek.days[5]
        val b = TodayBlock(
            EventRef("cal", "A"), "A", "A", "Academic", null, sat.atTime(11, 30), sat.atTime(14, 15), Tier.NORMAL, 30,
            listOf(Prayer.DHUHR, Prayer.ASR), isFixed = false,
        )
        val lines = CheckInPlanner.plan(sat, listOf(b), ReferenceWeek.anchorsOn(sat), settings).lines()
        assertEquals(listOf("11:30 START A", "11:48 PRAYER_BREAK DHUHR in A", "13:59 PRAYER_BREAK ASR in A", "14:15 END A"), lines)
    }

    @Test
    fun `prayer break pings can be switched off`() {
        assertEquals(
            listOf("11:30 START A", "12:45 END A"),
            run(block("A", "11:30", "12:45", buffer = 15), prefs = NotificationPrefs(midTaskPrayerPing = false)),
        )
    }

    @Test
    fun `an end ping inside a prayer slot moves to the end of the slot`() {
        // Anchor exactly at the end: 09:30 + 2h19 = 11:49 is Dhuhr, so the ping waits until 12:04.
        val plan = CheckInPlanner.plan(date, listOf(block("A", "09:30", "11:49")), anchors, settings)
        assertEquals(listOf("09:30 START A", "12:04 END A"), plan.lines())
        assertEquals(date.atTime(11, 49), plan.last().shiftedFrom)
    }

    @Test
    fun `quiet during prayer can be switched off`() {
        assertEquals(
            listOf("09:30 START A", "11:49 END A"),
            run(block("A", "09:30", "11:49"), prefs = NotificationPrefs(quietDuringPrayer = false)),
        )
    }

    @Test
    fun `an end ping pushed onto the next start by a prayer slot becomes Next up`() {
        // A ends 11:52 inside Dhuhr [11:49, 12:04); B starts 12:04, 12 minutes later but at the shifted ping.
        assertEquals(
            listOf("09:30 START A", "11:49 PRAYER_BREAK DHUHR in A", "12:04 NEXT_UP A -> B", "13:04 END B"),
            run(block("A", "09:30", "11:52"), block("B", "12:04", "13:04")),
        )
    }

    @Test
    fun `a prayer absorbed by a fixed event has no quiet slot`() {
        // Asr 14:05 falls inside the fixed 14:00-15:00 class: a block ending at 14:05 rings at 14:05.
        assertEquals(
            listOf("13:00 START A", "14:05 END A"),
            run(block("Class", "14:00", "15:00", fixed = true), block("A", "13:00", "14:05")),
        )
    }

    @Test
    fun `no pings after the hard cap`() {
        assertEquals(
            listOf("21:00 START A"),
            run(block("A", "21:00", "22:30")),
        )
        assertEquals(emptyList(), run(block("B", "22:05", "23:00")))
    }

    @Test
    fun `an end ping exactly at the hard cap still rings`() {
        assertEquals(listOf("21:00 START A", "22:00 END A"), run(block("A", "21:00", "22:00")))
    }

    @Test
    fun `a ping moved past the hard cap by a prayer slot is dropped`() {
        val late = listOf(PrayerAnchor(date, Prayer.ISHA, LocalTime.of(21, 50)))
        assertEquals(listOf("21:00 START A", "21:50 PRAYER_BREAK ISHA in A"), run(block("A", "21:00", "21:55"), a = late))
    }

    @Test
    fun `blocks not on the calendar get no pings`() {
        assertEquals(emptyList(), run(block("A", "09:30", "11:30", ref = false)))
    }

    @Test
    fun `everything off means no pings`() {
        assertEquals(emptyList(), run(block("A", "09:30", "11:30"), prefs = NotificationPrefs(checkInsEnabled = false)))
    }

    @Test
    fun `done, skipped and stopped blocks get no more pings`() {
        for (o in listOf(ExecutionOutcome.DONE, ExecutionOutcome.SKIPPED, ExecutionOutcome.STOPPED)) {
            assertEquals(emptyList(), run(block("A", "09:30", "11:30", outcome = o)), o.name)
        }
    }

    @Test
    fun `a started block gets no start ping but still its end ping`() {
        assertEquals(listOf("11:30 END A"), run(block("A", "09:30", "11:30", outcome = ExecutionOutcome.STARTED)))
    }

    @Test
    fun `a started next block is not merged into Next up`() {
        assertEquals(
            listOf("09:30 START A", "10:30 END A", "11:30 END B"),
            run(block("A", "09:30", "10:30"), block("B", "10:30", "11:30", outcome = ExecutionOutcome.STARTED)),
        )
    }

    @Test
    fun `blocks of other days are ignored`() {
        val other = block("A", "09:30", "11:30").let { it.copy(start = it.start.plusDays(1), end = it.end.plusDays(1)) }
        assertEquals(emptyList(), run(other))
    }

    @Test
    fun `identical input gives identical output`() {
        assertEquals(plan(1), plan(1))
    }

    // ---- Stable ids and rescheduling ----

    @Test
    fun `ids depend on the event, not the time`() {
        val before = CheckInPlanner.plan(date, listOf(block("A", "09:30", "11:30")), anchors, settings)
        val after = CheckInPlanner.plan(date, listOf(block("A", "12:05", "14:00")), anchors, settings)
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(listOf("start:cal/A", "end:cal/A"), before.map { it.id })
    }

    @Test
    fun `planDays covers several days in date order`() {
        val days = mapOf(
            ReferenceWeek.days[1] to (day(1) to ReferenceWeek.anchorsOn(ReferenceWeek.days[1])),
            ReferenceWeek.days[0] to (day(0) to ReferenceWeek.anchorsOn(ReferenceWeek.days[0])),
        )
        val all = CheckInPlanner.planDays(days, settings)
        assertEquals(plan(0) + plan(1), all)
        assertTrue(all.zipWithNext().all { (a, b) -> !b.at.isBefore(a.at) })
    }

    @Test
    fun `pings are always inside the day, never inside a live prayer slot except breaks`() {
        for (i in 0..6) {
            val d = ReferenceWeek.days[i]
            val fixed = day(i).filter { it.isFixed }
            val slots = ReferenceWeek.anchorsOn(d).map { d.atTime(it.begin) }
                .filter { a -> fixed.none { !a.isBefore(it.start) && a.isBefore(it.end) } }
            plan(i).filter { it.kind != PRAYER_BREAK }.forEach { c ->
                assertTrue(slots.none { !c.at.isBefore(it) && c.at.isBefore(it.plusMinutes(15)) }, "${c.id} at ${c.at}")
                assertTrue(!c.at.isAfter(d.atTime(settings.hardCap)))
            }
        }
    }
}
