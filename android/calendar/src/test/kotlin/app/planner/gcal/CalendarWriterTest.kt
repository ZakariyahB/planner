package app.planner.gcal

import app.planner.engine.changeset.Change
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.InverseOp
import app.planner.engine.changeset.PlannerTags
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.TaskStatus
import app.planner.engine.ports.AuthState
import app.planner.engine.ports.ChangeDraft
import app.planner.engine.ports.ProposalOutcome
import app.planner.gcal.Fixture.at
import app.planner.gcal.Fixture.cal
import app.planner.gcal.Fixture.day
import app.planner.gcal.plan.TemplateImporter
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** newplan 13.4: the approval flow over a fake calendar, including one that applies the wrong change. */
class CalendarWriterTest {
    private fun proposed(o: ProposalOutcome): ChangeSet = assertIs<ProposalOutcome.Proposed>(o, "got $o").changeSet

    private suspend fun mondayPlan(w: TestWorld) = proposed(w.service.planDays(listOf(day(0)), ChangeSource.USER))

    @Test
    fun `approve all applies, verifies, tags, links tasks and records the undo`() = runTest {
        val w = TestWorld().start()
        val cs = mondayPlan(w)
        val record = w.writer.apply(ApprovalGate.approveAll(cs))

        assertTrue(record.allVerified, "${record.results}")
        assertEquals(cs.changes.size, record.inverse.size)
        val sheet = w.eventsOn(day(0)).single { it.title == "P&S problem sheet" }
        assertEquals(EventTime.Timed(at(0, "09:30"), at(0, "11:30")), sheet.time)
        assertEquals("1", sheet.privateProps[PlannerTags.PLANNER])
        assertEquals(TemplateImporter.idFor(cal(CalendarCategory.ACADEMIC), "allday-ps-sheet-mon"), sheet.privateProps[PlannerTags.TASK_ID])
        assertEquals(EventRef(cal(CalendarCategory.ACADEMIC), "allday-ps-sheet-mon"), sheet.ref) // same event, no duplicate

        val task = w.tasks.get(sheet.taskId!!)!!
        assertEquals(TaskStatus.SCHEDULED, task.status)
        assertEquals(listOf(sheet.ref), task.calendarEventIds)
        assertEquals(record, w.changeSets.lastApplied())
        assertEquals(ChangeStatus.VERIFIED, w.changeSets.get(cs.id)!!.status)
        assertTrue(w.changeSets.observePending().first().isEmpty())
        assertTrue(w.reschedules > 0)
    }

    @Test
    fun `the writer's own changes are not mistaken for the user's edits on the next sync`() = runTest {
        val w = TestWorld().start()
        w.writer.apply(ApprovalGate.approveAll(mondayPlan(w)))
        w.sync.refresh()
        assertTrue(w.changeSets.observePending().first().isEmpty())
    }

    @Test
    fun `rejected changes are dropped and an empty approval writes nothing`() = runTest {
        val w = TestWorld().start()
        val cs = mondayPlan(w)
        val keep = cs.changes.first()
        val writes = w.gateway.writeLog.size
        val record = w.writer.apply(ApprovalGate.approve(cs, mapOf(keep.id to true)))
        assertEquals(listOf(keep.id), record.results.map { it.changeId })
        assertEquals(writes + 1, w.gateway.writeLog.size)
        val saved = w.changeSets.get(cs.id)!!
        assertTrue(saved.changes.filter { it.id != keep.id }.all { it.status == ChangeStatus.REJECTED })

        val other = mondayPlan(w)
        val before = w.gateway.writeLog.size
        val empty = w.writer.apply(ApprovalGate.approve(other, emptyMap()))
        assertTrue(empty.results.isEmpty())
        assertEquals(before, w.gateway.writeLog.size)
        assertEquals(ChangeStatus.REJECTED, w.changeSets.get(other.id)!!.status)
    }

    @Test
    fun `an event changed after the proposal is STALE, not written, and proposed again`() = runTest {
        val w = TestWorld().start()
        val cs = proposed(w.service.propose(listOf(ChangeDraft.MoveEvent(EventRef(cal(CalendarCategory.PERSONAL), "bol-wed"), day(3))), ChangeSource.USER, "Move BoL"))
        val gw = w.gateway as IncrementalFakeGateway
        gw.userEdit(EventRef(cal(CalendarCategory.PERSONAL), "bol-wed")) { it.copy(title = "BoL (room 2)") }
        val writes = w.gateway.writeLog.size

        val record = w.writer.apply(ApprovalGate.approveAll(cs))
        assertEquals(ChangeStatus.STALE, record.results.single().status)
        assertEquals(writes, w.gateway.writeLog.size)
        assertEquals("BoL (room 2)", w.event(EventRef(cal(CalendarCategory.PERSONAL), "bol-wed"))!!.title)

        val again = w.changeSets.observePending().first().single()
        assertEquals("BoL (room 2)", again.changes.single().target!!.title)
        assertEquals(ChangeStatus.PROPOSED, again.changes.single().status)
        // and the re-proposal applies cleanly
        assertTrue(w.writer.apply(ApprovalGate.approveAll(again)).allVerified)
    }

    @Test
    fun `a calendar that applies the wrong change is caught by verification`() = runTest {
        val w = TestWorld(gatewayFactory = ::MisbehavingGateway).start()
        val cs = mondayPlan(w)
        val record = w.writer.apply(ApprovalGate.approveAll(cs))
        assertTrue(record.results.all { it.status == ChangeStatus.FAILED }, "${record.results}")
        assertTrue(record.results.all { it.problem!!.startsWith("Google Calendar shows") }, "${record.results}")
        assertEquals(ChangeStatus.FAILED, w.changeSets.get(cs.id)!!.status)
        // the inverse ops are still kept, so the wrong writes can be undone
        assertEquals(cs.changes.size, record.inverse.size)
        (w.gateway as MisbehavingGateway).misbehave = false
        val undone = w.writer.undo(record)
        assertTrue(undone.undone, "${undone.results}")
        assertTrue(w.eventsOn(day(0)).filter { it.ref.calendarId != Fixture.PRAYER }.all { it.time is EventTime.AllDay })
    }

    @Test
    fun `inserts with the wrong title fail verification too`() = runTest {
        val w = TestWorld(gatewayFactory = ::MisbehavingGateway).start()
        val cs = proposed(w.service.propose(listOf(ChangeDraft.AddTask("Internship apps", durationMin = 60, day = day(6))), ChangeSource.USER, "x"))
        val r = w.writer.apply(ApprovalGate.approveAll(cs)).results.single()
        assertEquals(ChangeStatus.FAILED, r.status)
        assertTrue(r.problem!!.contains("INTERNSHIP APPS!"))
    }

    @Test
    fun `undo puts back edited, removed and added events`() = runTest {
        val w = TestWorld().start()
        val bol = EventRef(cal(CalendarCategory.PERSONAL), "bol-wed")
        val bolBefore = w.event(bol)!!
        val cs = proposed(
            w.service.propose(
                listOf(
                    ChangeDraft.RemoveEvent(bol),
                    ChangeDraft.AddTask("Internship apps", durationMin = 60, day = day(6)),
                    ChangeDraft.ScheduleTask(TemplateImporter.idFor(cal(CalendarCategory.ACADEMIC), "allday-pintos-tue")),
                ),
                ChangeSource.USER, "Three things",
            ),
        )
        val record = w.writer.apply(ApprovalGate.approveAll(cs))
        assertTrue(record.allVerified, "${record.results}")
        assertNull(w.event(bol))
        val added = record.results.single { r -> cs.changes.first { it.id == r.changeId }.type == ChangeType.ADD }.ref!!
        assertNotNull(w.event(added))
        assertTrue(record.inverse.first() is InverseOp.Revert) // reverse order: the last change is undone first

        val undone = w.writer.undo(record)
        assertTrue(undone.undone, "${undone.results}")
        assertEquals(bolBefore.title, w.event(bol)!!.title)
        assertEquals(bolBefore.time, w.event(bol)!!.time)
        assertNull(w.event(added))
        val pintos = w.event(EventRef(cal(CalendarCategory.ACADEMIC), "allday-pintos-tue"))!!
        assertIs<EventTime.AllDay>(pintos.time)
        val task = w.tasks.get(TemplateImporter.idFor(cal(CalendarCategory.ACADEMIC), "allday-pintos-tue"))!!
        assertEquals(TaskStatus.BACKLOG, task.status)
        assertTrue(task.calendarEventIds.isEmpty())
        assertEquals(undone, w.changeSets.lastApplied())
        // undoing twice does nothing more
        val writes = w.gateway.writeLog.size
        w.writer.undo(undone)
        assertEquals(writes, w.gateway.writeLog.size)
    }

    @Test
    fun `undo leaves alone an event the user changed after it was applied`() = runTest {
        val w = TestWorld().start()
        val record = w.writer.apply(ApprovalGate.approveAll(mondayPlan(w)))
        val sheet = w.eventsOn(day(0)).single { it.title == "P&S problem sheet" }
        (w.gateway as IncrementalFakeGateway).userEdit(sheet.ref) { it.copy(time = EventTime.Timed(at(0, "10:00"), at(0, "12:00"))) }
        val undone = w.writer.undo(record)
        assertFalse(undone.undone)
        assertEquals(EventTime.Timed(at(0, "10:00"), at(0, "12:00")), w.event(sheet.ref)!!.time)
        assertTrue(undone.results.any { it.problem?.contains("Changed in Google Calendar") == true })
    }

    @Test
    fun `retrying an applied ChangeSet is idempotent`() = runTest {
        val w = TestWorld().start()
        val cs = proposed(
            w.service.propose(
                listOf(ChangeDraft.AddTask("Internship apps", durationMin = 60, day = day(6)), ChangeDraft.MoveEvent(EventRef(cal(CalendarCategory.PERSONAL), "bol-wed"), day(3))),
                ChangeSource.USER, "x",
            ),
        )
        val first = w.writer.apply(ApprovalGate.approveAll(cs))
        assertTrue(first.allVerified)
        val count = w.gateway.events(day(0), day(6).plusDays(1)).size
        val writes = w.gateway.writeLog.size
        val second = w.writer.apply(ApprovalGate.approveAll(cs))
        assertTrue(second.allVerified, "${second.results}")
        assertEquals(writes, w.gateway.writeLog.size)
        assertEquals(count, w.gateway.events(day(0), day(6).plusDays(1)).size)
        assertEquals(first.inverse, second.inverse)
    }

    @Test
    fun `a retry after a partial failure finishes the rest without duplicating the done part`() = runTest {
        val w = TestWorld().start()
        val cs = mondayPlan(w)
        val gw = w.gateway
        // first change succeeds, then sign-in expires
        val firstOnly = ApprovalGate.approve(cs, mapOf(cs.changes[0].id to true))
        w.writer.apply(firstOnly)
        val record = w.writer.apply(ApprovalGate.approveAll(cs))
        assertTrue(record.allVerified, "${record.results}")
        assertEquals(1, gw.events(day(0), day(1)).count { it.title == cs.changes[0].after!!.title })
    }

    @Test
    fun `sign-in expiry stops the run without crashing and queues nothing`() = runTest {
        val w = TestWorld().start()
        val cs = mondayPlan(w)
        w.gateway.authExpired = true
        val record = w.writer.apply(ApprovalGate.approveAll(cs))
        assertTrue(record.results.all { it.status == ChangeStatus.FAILED && it.problem == app.planner.gcal.write.DefaultCalendarWriter.SIGN_IN })
        assertEquals(AuthState.EXPIRED, w.auth.state.value)
        assertTrue(w.changeSets.observePending().first().isEmpty()) // nothing re-queued behind the user's back
        assertEquals(ChangeStatus.FAILED, w.changeSets.get(cs.id)!!.status)

        // sync reports expiry as a stamp, not a crash
        val stamp = w.sync.refresh()
        assertFalse(stamp.ok)
        assertEquals(SyncMessages.SIGN_IN_AGAIN, stamp.message)
    }

    @Test
    fun `expiry in the middle fails the remaining changes and writes none of them`() = runTest {
        val w = TestWorld(gatewayFactory = { c, e, k -> ExpiringGateway(c, e, k, writesAllowed = 1) }).start()
        val cs = mondayPlan(w)
        val record = w.writer.apply(ApprovalGate.approveAll(cs))
        assertEquals(ChangeStatus.VERIFIED, record.results[0].status)
        assertTrue(record.results.drop(1).all { it.status == ChangeStatus.FAILED })
        assertEquals(1, w.gateway.writeLog.size)
        assertEquals(1, record.inverse.size)
    }

    @Test
    fun `prayer times and read-only calendars are refused even in an approved ChangeSet`() = runTest {
        val w = TestWorld().start()
        val prayer = w.event(EventRef(Fixture.PRAYER, "prayer-asr-${day(0)}"))!!
        val bad = ChangeSet(
            "bad", ChangeSource.USER, "x",
            listOf(
                Change("c1", ChangeType.REMOVE, target = prayer),
                Change("c2", ChangeType.ADD, after = EventDraft(Fixture.HOLIDAYS, "x", EventTime.Timed(at(0, "09:30"), at(0, "10:00"))), newEventId = EventIds.from("x")),
            ),
            Instant.EPOCH,
        )
        val writes = w.gateway.writeLog.size
        val record = w.writer.apply(ApprovalGate.approveAll(bad))
        assertTrue(record.results.all { it.status == ChangeStatus.FAILED })
        assertEquals(writes, w.gateway.writeLog.size)
        assertNotNull(w.event(prayer.ref))
    }

    @Test
    fun `invalid client ids are refused`() = runTest {
        val w = TestWorld().start()
        val bad = ChangeSet(
            "bad", ChangeSource.USER, "x",
            listOf(Change("c1", ChangeType.ADD, after = EventDraft(cal(CalendarCategory.PERSONAL), "x", EventTime.Timed(at(0, "09:30"), at(0, "10:00"))), newEventId = "Not_Base32!")),
            Instant.EPOCH,
        )
        assertEquals(ChangeStatus.FAILED, w.writer.apply(ApprovalGate.approveAll(bad)).results.single().status)
    }

    @Test
    fun `recurring scope goes to the calendar as approved, this instance by default`() = runTest {
        val series = (0..2).map { i ->
            Fixture.snapshot(cal(CalendarCategory.PERSONAL), "quran-class_${i}", "Quran class (Sh Anass)", EventTime.Timed(at(3, "18:00").plusWeeks(i.toLong()), at(3, "18:30").plusWeeks(i.toLong())), recurring = "quran-class")
        }
        val events = Fixture.allEvents().filter { it.ref.eventId != "quran-class-thu" } + series
        val w = TestWorld(events = events, gatewayFactory = ::RecurringFakeGateway).start()

        val one = proposed(w.service.propose(listOf(ChangeDraft.RemoveEvent(series[0].ref)), ChangeSource.USER, "x"))
        assertEquals(RecurrenceScope.THIS_INSTANCE, one.changes.single().scope)
        assertTrue(w.writer.apply(ApprovalGate.approveAll(one)).allVerified)
        assertTrue(w.gateway.writeLog.last().endsWith("THIS_INSTANCE"))
        assertNotNull(w.event(series[1].ref))

        val all = proposed(w.service.propose(listOf(ChangeDraft.RemoveEvent(series[1].ref, RecurrenceScope.WHOLE_SERIES)), ChangeSource.USER, "x"))
        val record = w.writer.apply(ApprovalGate.approveAll(all))
        assertTrue(record.allVerified)
        assertNull(w.event(series[2].ref))
        assertTrue(record.inverse.isEmpty()) // whole-series removals cannot be undone from a snapshot
        assertTrue(record.results.single().problem!!.contains("Undo is not available"))
    }

    @Test
    fun `apply reschedules check-ins and refreshes`() = runTest {
        val w = TestWorld().start()
        val before = w.reschedules
        w.writer.apply(ApprovalGate.approveAll(mondayPlan(w)))
        assertTrue(w.reschedules > before)
    }
}

/** Lets [writesAllowed] writes through, then reports the Google sign-in as expired. */
class ExpiringGateway(
    calendars: List<app.planner.engine.ports.CalendarInfo>,
    initial: List<app.planner.engine.changeset.EventSnapshot>,
    clock: () -> Instant,
    private val writesAllowed: Int,
) : IncrementalFakeGateway(calendars, initial, clock) {
    override suspend fun patch(ref: EventRef, draft: EventDraft, scope: RecurrenceScope): app.planner.engine.changeset.EventSnapshot {
        if (writeLog.size >= writesAllowed) { authExpired = true; throw CalendarException.AuthExpired() }
        return super.patch(ref, draft, scope)
    }

    override suspend fun insert(calendarId: String, eventId: String, draft: EventDraft): app.planner.engine.changeset.EventSnapshot {
        if (writeLog.size >= writesAllowed) { authExpired = true; throw CalendarException.AuthExpired() }
        return super.insert(calendarId, eventId, draft)
    }
}
