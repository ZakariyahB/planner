package app.planner.gcal

import app.planner.engine.changeset.ChangeFlag
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.PlannerTags
import app.planner.engine.model.CalendarCategory
import app.planner.engine.ports.ProposalOutcome
import app.planner.gcal.Fixture.at
import app.planner.gcal.Fixture.cal
import app.planner.gcal.Fixture.day
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Stream A's engine splits tasks around fixed events; the interim engine does not, so this runs on the real one. */
class SplitChunksTest {
    @Test
    fun `a split template task edits its all-day event into chunk 1 and adds the rest`() = runTest {
        val halaqa = Fixture.snapshot(cal(CalendarCategory.PERSONAL), "halaqa-wed", "Halaqa", EventTime.Timed(at(2, "14:00"), at(2, "16:30")))
        val w = TestWorld(events = Fixture.allEvents() + halaqa).start()
        val cs = assertIs<ProposalOutcome.Proposed>(w.service.planDays(listOf(day(2)), ChangeSource.USER)).changeSet
        val chunks = cs.changes.filter { Regex("""\(\d/\d\)$""").containsMatchIn(it.after!!.title) }
        assertTrue(chunks.isNotEmpty(), "Wednesday should need splitting: ${cs.changes.map { it.after!!.title + " " + it.after!!.time }}")
        for ((title, parts) in chunks.groupBy { it.after!!.title.substringBefore(" (") }) {
            val first = parts.single { it.after!!.title.endsWith("(1/${parts.size})") }
            assertEquals(ChangeType.EDIT, first.type, title)
            assertTrue(ChangeFlag.CONVERTS_ALL_DAY in first.flags)
            assertTrue(parts.filter { it !== first }.all { it.type == ChangeType.ADD && EventIds.isValid(it.newEventId!!) })
            assertTrue(parts.all { it.after!!.description!!.contains("part ") })
            assertEquals((1..parts.size).map { "$it/${parts.size}" }.toSet(), parts.map { it.after!!.privateProps[PlannerTags.CHUNK] }.toSet())
        }
        // and the whole plan applies and verifies
        val record = w.writer.apply(ApprovalGate.approveAll(cs))
        assertTrue(record.allVerified, "${record.results}")
        val task = w.tasks.get(chunks.first().taskId!!)!!
        assertEquals(chunks.count { it.taskId == task.id }, task.calendarEventIds.size)
    }
}
