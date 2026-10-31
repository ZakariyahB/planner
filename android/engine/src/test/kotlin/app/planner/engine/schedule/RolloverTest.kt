package app.planner.engine.schedule

import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.EventRef
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.TaskStatus
import app.planner.engine.prayer.DefaultPrayerModel
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** newplan 5.7: "copy schedule template into next week" re-times rather than copies. */
class RolloverTest {
    private val engine = Engine.planning
    private val template = ReferenceWeek.monday
    private val target = template.plusDays(7)
    private val targetDays = (0L..6L).map { target.plusDays(it) }

    /** Next week's weekday times: every prayer 3 minutes earlier, then the weekend interpolated. */
    private val targetAnchors = DefaultPrayerModel.resolve(
        targetDays,
        ReferenceWeek.importedAnchors.map { it.copy(date = it.date.plusDays(7), begin = it.begin.minusMinutes(3)) },
        Settings(),
    )

    private val recurring = ReferenceWeek.fixed.filter { it.title == "Halaqa" || it.title == "Jumu'ah prep" }
    private val targetFixed = ReferenceWeek.fixed.filter { it.title == "BoL" }.map { it.copy(start = it.start.plusDays(7), end = it.end.plusDays(7)) } +
        // Jumu'ah prep is on next week's calendar; the Halaqa is not.
        recurring.filter { it.title == "Jumu'ah prep" }.map { it.copy(start = it.start.plusDays(7), end = it.end.plusDays(7), assumed = false, ref = EventRef("cal-society", "jumuah-real")) }

    private fun rollover(settings: Settings = Settings(), tasks: List<app.planner.engine.model.Task> = ReferenceWeek.tasks) = engine.rollover(
        RolloverRequest(tasks, template, target, targetFixed, recurring, targetAnchors, settings),
    )

    @Test fun `tasks shift by 7 days with new deterministic ids and fresh state`() {
        val r = rollover()
        assertEquals(ReferenceWeek.tasks.size, r.tasks.size)
        r.tasks.zip(ReferenceWeek.tasks).forEach { (new, old) ->
            assertEquals(old.assignedDay!!.plusDays(7), new.assignedDay)
            assertNotEquals(old.id, new.id)
            assertEquals(old.durationMin, new.durationMin)
            assertEquals(old.earliestStart, new.earliestStart)
            assertEquals(old.afterPrayer, new.afterPrayer)
            assertEquals(TaskStatus.BACKLOG, new.status)
            assertTrue(new.calendarEventIds.isEmpty())
            assertEquals(null, new.sourceEvent)
        }
        assertEquals(r.tasks.map { it.id }, rollover().tasks.map { it.id })
        assertEquals(r.tasks.size, r.tasks.map { it.id }.toSet().size)
    }

    @Test fun `re-anchored to the target week's prayer times`() {
        val r = rollover()
        val ids = r.tasks.associate { it.id to it }
        // Tue "Arabic hw, after Asr": Asr is 14:01 next Tuesday, so its slot ends 14:16, but the lecture holds 14:00-15:15.
        val tueLecture = r.plan.placements.single { ids.getValue(it.taskId).title == "Lecture - SDE" && it.start.toLocalDate() == target.plusDays(1) }
        assertEquals(target.plusDays(1).atTime(14, 0), tueLecture.start)
        assertEquals(listOf(Prayer.ASR), tueLecture.coveredPrayers)
        // Mon P&S sheet: Dhuhr moves to 11:46, so a 2h block from 09:30 still ends 11:30 with no buffer.
        val sheet = r.plan.placements.single { ids.getValue(it.taskId).title == "P&S problem sheet" }
        assertEquals(target.atTime(9, 30) to target.atTime(11, 30), sheet.start to sheet.end)
        // Every placement satisfies the invariants against the target week.
        val request = PlanRequest(targetDays, r.tasks, targetFixed + r.plan.warnings.filterIsInstance<PlanWarning.AssumedGapKept>().let { recurringIn(it) }, targetAnchors, Settings())
        assertEquals(emptyList(), PlanInvariants.check(request, r.plan))
        assertTrue(r.plan.placements.all { it.start.toLocalDate() in targetDays })
    }

    private fun recurringIn(gaps: List<PlanWarning.AssumedGapKept>) = gaps.map { g ->
        val e = recurring.single { it.title == g.title }
        e.copy(start = e.start.plusDays(7), end = e.end.plusDays(7), assumed = true)
    }

    @Test fun `a missing recurring event keeps its gap open and says so`() {
        val r = rollover()
        val wed = target.plusDays(2)
        assertEquals(listOf<PlanWarning>(PlanWarning.AssumedGapKept(wed, "Halaqa")), r.plan.warnings.filterIsInstance<PlanWarning.AssumedGapKept>())
        assertTrue(r.notes.any { "Halaqa" in it && "14:00-16:30" in it })
        val from = wed.atTime(14, 0)
        val to = wed.atTime(16, 30)
        assertTrue(r.plan.placements.none { it.start.isBefore(to) && from.isBefore(it.end) })
    }

    @Test fun `a recurring event already on the calendar is not added again`() {
        val r = rollover()
        assertTrue(r.notes.none { "Jumu'ah" in it })
        val fri = target.plusDays(4)
        assertTrue(r.plan.placements.none { it.start.isBefore(fri.atTime(14, 0)) && fri.atTime(12, 0).isBefore(it.end) })
    }

    @Test fun `week tags are kept by default`() {
        val tagged = ReferenceWeek.tasks.map { if (it.title == "PintOS") it.copy(title = "PintOS w-1") else it }
        val r = rollover(tasks = tagged)
        assertEquals(3, r.tasks.count { it.title == "PintOS w-1" })
        assertTrue(r.notes.any { it.startsWith("Kept week tags in 3") })
    }

    @Test fun `week tags are stripped when the setting says so`() {
        val tagged = ReferenceWeek.tasks.map { if (it.title == "PintOS") it.copy(title = "PintOS (w-1)") else it }
        val r = rollover(Settings(stripWeekTagsOnRollover = true), tagged)
        assertEquals(3, r.tasks.count { it.title == "PintOS" })
        assertTrue(r.notes.any { it.startsWith("Removed week tags from 3") })
    }

    @Test fun `the first note says what was copied`() {
        assertEquals(
            "Copied 23 template tasks from the week of Mon 2 Nov into the week of Mon 9 Nov, timed to that week's prayer times and events.",
            rollover().notes.first(),
        )
    }

    @Test fun `weekend anchors in the target week are interpolated and flagged`() {
        val r = rollover()
        val sat = target.plusDays(5)
        assertTrue(DayFlag.INTERPOLATED_PRAYERS in r.plan.days.getValue(sat).flags)
        assertTrue(targetAnchors.filter { it.date == sat }.all { it.interpolated })
        assertEquals(PrayerAnchor(sat, Prayer.ASR, LocalTime.of(13, 56), interpolated = true), targetAnchors.single { it.date == sat && it.prayer == Prayer.ASR })
    }
}
