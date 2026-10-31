package app.planner.gcal

import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventTime
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.TaskStatus
import app.planner.engine.ports.AuthState
import app.planner.engine.ports.ProposalOutcome
import app.planner.gcal.Fixture.at
import app.planner.gcal.Fixture.cal
import app.planner.gcal.Fixture.day
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class CalendarSyncTest {
    private val bol = EventRef(cal(CalendarCategory.PERSONAL), "bol-wed")

    @Test
    fun `first sync is full, later ones that day are incremental`() = runTest {
        val w = TestWorld()
        val gw = w.gateway as IncrementalFakeGateway
        w.sync.refresh()
        assertTrue(gw.syncCalls.all { it.second == null })
        assertNotNull(w.snapshots.events(day(2), day(3)).firstOrNull { it.ref == bol })
        gw.syncCalls.clear()
        w.sync.refresh()
        assertTrue(gw.syncCalls.isNotEmpty() && gw.syncCalls.all { it.second != null })
    }

    @Test
    fun `a new day starts with a full sync, since the window has moved`() = runTest {
        val w = TestWorld()
        val gw = w.gateway as IncrementalFakeGateway
        w.sync.refresh()
        w.now = w.now.plusDays(1)
        gw.syncCalls.clear()
        w.sync.refresh()
        assertTrue(gw.syncCalls.all { it.second == null })
    }

    @Test
    fun `an expired sync token falls back to a full fetch`() = runTest {
        val w = TestWorld()
        val gw = w.gateway as IncrementalFakeGateway
        w.sync.refresh()
        gw.userDelete(bol)
        gw.tokenGone = true
        gw.syncCalls.clear()
        val stamp = w.sync.refresh()
        assertTrue(stamp.ok)
        assertTrue(gw.syncCalls.any { it.second == null })
        assertNull(w.snapshots.events(day(2), day(3)).firstOrNull { it.ref == bol })
    }

    @Test
    fun `incremental sync applies changes and deletions`() = runTest {
        val w = TestWorld()
        val gw = w.gateway as IncrementalFakeGateway
        w.sync.refresh()
        gw.userEdit(bol) { it.copy(title = "BoL moved") }
        gw.userDelete(EventRef(cal(CalendarCategory.PERSONAL), "quran-class-thu"))
        val before = w.reschedules
        w.sync.refresh()
        assertEquals("BoL moved", w.snapshots.events(day(2), day(3)).first { it.ref == bol }.title)
        assertTrue(w.snapshots.events(day(3), day(4)).none { it.ref.eventId == "quran-class-thu" })
        assertTrue(w.reschedules > before)
    }

    @Test
    fun `no changes, no check-in reschedule`() = runTest {
        val w = TestWorld()
        w.sync.refresh()
        val before = w.reschedules
        w.sync.refresh()
        assertEquals(before, w.reschedules)
    }

    @Test
    fun `the user's own move is final and a clash gets a replan proposal`() = runTest {
        val w = TestWorld().start()
        applyAll(w, w.service.planDays(listOf(day(0)), ChangeSource.USER))
        val gw = w.gateway as IncrementalFakeGateway
        val sheet = w.eventsOn(day(0)).single { it.title == "P&S problem sheet" }
        // the user drags the problem sheet on top of Lecture - CP2 (11:30-12:45)
        gw.userEdit(sheet.ref) { it.copy(time = EventTime.Timed(at(0, "11:00"), at(0, "13:15"))) }
        w.sync.refresh()

        assertEquals(EventTime.Timed(at(0, "11:00"), at(0, "13:15")), w.event(sheet.ref)!!.time) // untouched
        val pending = w.changeSets.observePending().first()
        val replan = pending.single()
        assertEquals(ChangeSource.REPLAN, replan.source)
        assertTrue(replan.changes.all { it.type == ChangeType.MOVE && it.target!!.ref != sheet.ref })
        assertTrue(replan.changes.any { it.target!!.title == "Lecture - CP2" })
    }

    @Test
    fun `a move that breaks nothing proposes nothing`() = runTest {
        val w = TestWorld().start()
        applyAll(w, w.service.planDays(listOf(day(0)), ChangeSource.USER))
        val gw = w.gateway as IncrementalFakeGateway
        val sheet = w.eventsOn(day(0)).single { it.title == "P&S problem sheet" }
        gw.userEdit(sheet.ref) { it.copy(time = EventTime.Timed(at(0, "09:00"), at(0, "11:00"))) }
        w.sync.refresh()
        assertTrue(w.changeSets.observePending().first().isEmpty())
    }

    @Test
    fun `the user deleting a block sends its task back to the backlog`() = runTest {
        val w = TestWorld().start()
        applyAll(w, w.service.planDays(listOf(day(0)), ChangeSource.USER))
        val sheet = w.eventsOn(day(0)).single { it.title == "P&S problem sheet" }
        (w.gateway as IncrementalFakeGateway).userDelete(sheet.ref)
        w.sync.refresh()
        val task = w.tasks.get(sheet.taskId!!)!!
        assertEquals(TaskStatus.BACKLOG, task.status)
        assertTrue(task.calendarEventIds.isEmpty())
    }

    @Test
    fun `sign-in expiry is a failed stamp and an expired state, never an exception`() = runTest {
        val w = TestWorld()
        w.gateway.authExpired = true
        val stamp = w.sync.refresh()
        assertFalse(stamp.ok)
        assertEquals(AuthState.EXPIRED, w.auth.state.value)
        assertEquals(stamp, w.snapshots.observeSync().first())
        w.gateway.authExpired = false
        assertTrue(w.sync.refresh().ok)
        assertEquals(AuthState.SIGNED_IN, w.auth.state.value)
    }

    @Test
    fun `refresh through the planner queries`() = runTest {
        val w = TestWorld()
        assertTrue(w.service.refresh().ok)
        assertTrue(w.service.findEvents("bol", day(0), day(6)).isNotEmpty())
    }

    @Test
    fun `replan on a user edit can say nothing needs moving`() = runTest {
        val w = TestWorld().start()
        val o = w.service.replan(app.planner.engine.ports.ReplanReason.UserEdited(listOf(bol)), ChangeSource.REPLAN)
        assertTrue(o is ProposalOutcome.NothingToDo, "$o")
    }
}
