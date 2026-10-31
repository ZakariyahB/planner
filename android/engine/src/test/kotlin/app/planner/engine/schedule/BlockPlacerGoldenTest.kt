package app.planner.engine.schedule

import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.EventRef
import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Prayer
import app.planner.engine.model.Prayer.ASR
import app.planner.engine.model.Prayer.DHUHR
import app.planner.engine.model.Prayer.ISHA
import app.planner.engine.model.Prayer.MAGHRIB
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.model.CalendarCategory
import app.planner.engine.prayer.DefaultPrayerModel
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** newplan 13.1: every golden case and edge case is its own test. Anchors are the reference week's. */
class BlockPlacerGoldenTest {
    private val placer: BlockPlacer = DefaultBlockPlacer

    private fun at(day: Int, time: String): LocalDateTime = ReferenceWeek.days[day].atTime(LocalTime.parse(time))

    private fun fits(day: Int, start: String, durationMin: Int, fixed: List<FixedEvent> = emptyList()): BlockFit.Fits {
        val r = placer.place(at(day, start), durationMin, ReferenceWeek.anchorsOn(ReferenceWeek.days[day]), fixed)
        return assertIs<BlockFit.Fits>(r)
    }

    private fun assertBlock(day: Int, start: String, end: String, buffer: Int, covered: List<Prayer>, fit: BlockFit.Fits) {
        assertEquals(at(day, start), fit.start, "start")
        assertEquals(at(day, end), fit.end, "end")
        assertEquals(buffer, fit.bufferMin, "buffer")
        assertEquals(covered, fit.coveredPrayers, "covered prayers")
    }

    private val MON = 0
    private val TUE = 1
    private val WED = 2
    private val THU = 3
    private val FRI = 4
    private val SAT = 5
    private val SUN = 6

    // Golden cases: (start, duration) -> end, buffer.

    @Test fun `Mon 0930 2h covers no prayer - ends 1130 with no buffer`() =
        assertBlock(MON, "09:30", "11:30", 0, emptyList(), fits(MON, "09:30", 120))

    @Test fun `Mon 1130 1h covers Dhuhr - ends 1245 with 15`() =
        assertBlock(MON, "11:30", "12:45", 15, listOf(DHUHR), fits(MON, "11:30", 60))

    @Test fun `Mon 1400 1h covers Asr - ends 1515 with 15`() =
        assertBlock(MON, "14:00", "15:15", 15, listOf(ASR), fits(MON, "14:00", 60))

    @Test fun `Mon 1700 2h covers Isha - ends 1915 with 15`() =
        assertBlock(MON, "17:00", "19:15", 15, listOf(ISHA), fits(MON, "17:00", 120))

    @Test fun `Tue 0930 3h covers Dhuhr - ends 1245 with 15`() =
        assertBlock(TUE, "09:30", "12:45", 15, listOf(DHUHR), fits(TUE, "09:30", 180))

    @Test fun `Thu 1500 2h covers Maghrib - ends 1715 with 15`() =
        assertBlock(THU, "15:00", "17:15", 15, listOf(MAGHRIB), fits(THU, "15:00", 120))

    @Test fun `Fri 1515 2h covers Maghrib - ends 1730 with 15`() =
        assertBlock(FRI, "15:15", "17:30", 15, listOf(MAGHRIB), fits(FRI, "15:15", 120))

    @Test fun `Sat 1130 2h covers Dhuhr - ends 1345 with 15`() =
        assertBlock(SAT, "11:30", "13:45", 15, listOf(DHUHR), fits(SAT, "11:30", 120))

    @Test fun `Sun 1315 1h covers Asr - ends 1430 with 15`() =
        assertBlock(SUN, "13:15", "14:30", 15, listOf(ASR), fits(SUN, "13:15", 60))

    @Test fun `Sun 1430 2h covers Maghrib - ends 1645 with 15`() =
        assertBlock(SUN, "14:30", "16:45", 15, listOf(MAGHRIB), fits(SUN, "14:30", 120))

    @Test fun `Sun 1645 2h covers Isha - ends 1900 with 15`() =
        assertBlock(SUN, "16:45", "19:00", 15, listOf(ISHA), fits(SUN, "16:45", 120))

    // Edge cases.

    @Test fun `start inside a prayer slot moves to the slot end - Fri 1400 at Asr starts 1415 ends 1515 with no buffer`() =
        assertBlock(FRI, "14:15", "15:15", 0, emptyList(), fits(FRI, "14:00", 60))

    @Test fun `start anywhere inside the slot moves to its end - Fri 1414 starts 1415`() =
        assertBlock(FRI, "14:15", "15:15", 0, emptyList(), fits(FRI, "14:14", 60))

    @Test fun `start exactly at the slot end stays - Fri 1415`() =
        assertBlock(FRI, "14:15", "15:15", 0, emptyList(), fits(FRI, "14:15", 60))

    @Test fun `cascade - Sat 1130 2h15 ends 1415 with 30 as Dhuhr pushes the end past Asr`() =
        assertBlock(SAT, "11:30", "14:15", 30, listOf(DHUHR, ASR), fits(SAT, "11:30", 135))

    @Test fun `cascade across three prayers - Mon 1100 6h covers Dhuhr Asr and Maghrib`() {
        // 11:00 + 6h = 17:00 covers Dhuhr, Asr, Maghrib (+45 = 17:45), which does not reach Isha 18:03.
        assertBlock(MON, "11:00", "17:45", 45, listOf(DHUHR, ASR, MAGHRIB), fits(MON, "11:00", 360))
        // One more minute of buffer reach: 6h20 ends 17:20 + 45 = 18:05, past Isha 18:03, so Isha cascades in.
        assertBlock(MON, "11:00", "18:20", 60, listOf(DHUHR, ASR, MAGHRIB, ISHA), fits(MON, "11:00", 380))
    }

    @Test fun `anchor exactly at end does not count - Mon 0930 2h19 ends 1149 with no buffer`() =
        assertBlock(MON, "09:30", "11:49", 0, emptyList(), fits(MON, "09:30", 139))

    @Test fun `anchor one minute before the end counts - Mon 0930 2h20 ends 1205`() =
        assertBlock(MON, "09:30", "12:05", 15, listOf(DHUHR), fits(MON, "09:30", 140))

    @Test fun `buffer landing exactly on the next anchor does not cascade`() {
        // Sat Dhuhr 11:48, Asr 13:59: 11:44 + 2h00 = 13:44, +15 = 13:59 = Asr exactly, so Asr is not covered.
        assertBlock(SAT, "11:44", "13:59", 15, listOf(DHUHR), fits(SAT, "11:44", 120))
    }

    @Test fun `absorbed prayer - Wed Halaqa 1400-1630 contains Asr 1402 so no slot and no task overlaps it`() {
        val wed = ReferenceWeek.days[WED]
        val halaqa = ReferenceWeek.fixed.single { it.title == "Halaqa" }
        val absorbed = DefaultPrayerModel.absorb(ReferenceWeek.anchorsOn(wed), ReferenceWeek.fixed)
        // Asr 14:02 is inside the Halaqa; Dhuhr 11:49 is inside BoL 10:15-12:45; Maghrib 16:31 is after the Halaqa ends.
        assertEquals(listOf(MAGHRIB, ISHA), absorbed.map { it.prayer })

        // A start at the Asr begin time is not moved: there is no slot. It collides with the Halaqa instead.
        val inside = placer.place(at(WED, "14:02"), 30, absorbed, ReferenceWeek.fixed)
        assertIs<BlockFit.Collides>(inside)
        assertEquals(halaqa, inside.with)
        assertEquals(at(WED, "14:02"), inside.start)

        // A block running into the Halaqa collides.
        val into = placer.place(at(WED, "13:30"), 60, absorbed, ReferenceWeek.fixed)
        assertIs<BlockFit.Collides>(into)
        assertEquals(halaqa, into.with)

        // A block ending exactly at the Halaqa start fits, without any Asr buffer.
        assertBlock(WED, "13:00", "14:00", 0, emptyList(), assertIs(placer.place(at(WED, "13:00"), 60, absorbed, ReferenceWeek.fixed)))

        // The engine never places anything inside the Halaqa, and nothing covers Asr that day.
        val task = Task("t", "Long task", CalendarCategory.ACADEMIC, 300, assignedDay = wed)
        val plan = Engine.planning.plan(PlanRequest(listOf(wed), listOf(task), ReferenceWeek.fixed, ReferenceWeek.anchors, Settings()))
        assertTrue(plan.placements.isNotEmpty())
        plan.placements.forEach { p ->
            assertTrue(!(p.start.isBefore(halaqa.end) && halaqa.start.isBefore(p.end)), "$p overlaps the Halaqa")
            assertTrue(ASR !in p.coveredPrayers, "$p covers an absorbed Asr")
        }
    }

    @Test fun `fixed event overlap - a block running into a fixed event is invalid`() {
        val bol = ReferenceWeek.fixed.single { it.title == "BoL" }
        // Wed 09:30 + 1h would end 10:30, inside BoL 10:15-12:45.
        val r = placer.place(at(WED, "09:30"), 60, ReferenceWeek.anchorsOn(ReferenceWeek.days[WED]), ReferenceWeek.fixed)
        assertIs<BlockFit.Collides>(r)
        assertEquals(bol, r.with)
        assertEquals(at(WED, "10:30"), r.end)
        // Ending exactly at the fixed start is fine.
        assertIs<BlockFit.Fits>(placer.place(at(WED, "09:30"), 45, ReferenceWeek.anchorsOn(ReferenceWeek.days[WED]), ReferenceWeek.fixed))
    }

    @Test fun `fixed event overlap - the engine tries the next gap or splits`() {
        val wed = ReferenceWeek.days[WED]
        val bol = ReferenceWeek.fixed.single { it.title == "BoL" }
        val oneHour = Task("one", "One hour", CalendarCategory.ACADEMIC, 60, assignedDay = wed, splittable = false)
        val plan = Engine.planning.plan(PlanRequest(listOf(wed), listOf(oneHour), listOf(bol), ReferenceWeek.anchors, Settings()))
        // 09:30-10:15 is too short; the next gap starts when BoL ends at 12:45 (Dhuhr 11:49 is absorbed by BoL).
        assertEquals(listOf(at(WED, "12:45") to at(WED, "13:45")), plan.placements.map { it.start to it.end })

        val ninety = Task("ninety", "Ninety", CalendarCategory.ACADEMIC, 90, assignedDay = wed)
        val busy = listOf(bol, FixedEvent(EventRef("c", "x"), "Busy", at(WED, "13:30"), at(WED, "16:00")))
        val split = Engine.planning.plan(PlanRequest(listOf(wed), listOf(ninety), busy, ReferenceWeek.anchors, Settings()))
        // 90 does not fit whole before 16:31 (Maghrib slot ends 16:46): 45 before BoL, 45 after it.
        assertEquals(
            listOf(at(WED, "09:30") to at(WED, "10:15"), at(WED, "12:45") to at(WED, "13:30")),
            split.placements.map { it.start to it.end },
        )
        assertEquals(listOf("(1/2)", "(2/2)"), split.placements.map { it.label })
    }

    @Test fun `no Friday Dhuhr - no Dhuhr slot on Fridays`() {
        val fri = ReferenceWeek.days[FRI]
        val resolved = DefaultPrayerModel.resolve(listOf(fri), ReferenceWeek.importedAnchors, Settings())
        assertTrue(resolved.none { it.prayer == DHUHR })
        // Even if an import carries one, it is dropped.
        val withDhuhr = ReferenceWeek.importedAnchors + PrayerAnchor(fri, DHUHR, LocalTime.of(11, 49))
        assertTrue(DefaultPrayerModel.resolve(listOf(fri), withDhuhr, Settings()).none { it.prayer == DHUHR })
        // So a block across 11:49 on Friday gets no buffer and a start at 11:49 is not moved.
        assertBlock(FRI, "09:30", "12:00", 0, emptyList(), fits(FRI, "09:30", 150))
        assertBlock(FRI, "11:49", "12:49", 0, emptyList(), fits(FRI, "11:49", 60))
    }

    @Test fun `Fajr is never an anchor`() {
        val mon = ReferenceWeek.days[MON]
        val fajr = PrayerAnchor(mon, Prayer.FAJR, LocalTime.of(10, 0))
        val r = placer.place(at(MON, "09:30"), 60, listOf(fajr), emptyList())
        assertBlock(MON, "09:30", "10:30", 0, emptyList(), assertIs(r))
        assertTrue(DefaultPrayerModel.resolve(listOf(mon), ReferenceWeek.importedAnchors + fajr, Settings()).none { it.prayer == Prayer.FAJR })
    }
}
