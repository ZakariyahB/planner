package app.planner.engine.checkin

import app.planner.engine.changeset.ChangeSource
import app.planner.engine.fakes.FakeProposalPort
import app.planner.engine.fakes.InMemoryChangeSetStore
import app.planner.engine.fakes.InMemoryExecutionLogStore
import app.planner.engine.model.EventRef
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.NotificationPrefs
import app.planner.engine.model.Prayer
import app.planner.engine.ports.ProposalOutcome
import app.planner.engine.ports.ReplanReason
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class CheckInResponseTest {
    private val start = LocalDateTime.of(2026, 11, 3, 9, 30)
    private val block = CheckInBlock(EventRef("cal-academic", "pintos"), "pintos-tue", "PintOS", start, start.plusMinutes(195), 15, listOf(Prayer.DHUHR))
    private val next = CheckInBlock(EventRef("cal-lectures", "sde"), "lec-sde-tue", "Lecture - SDE", start.plusMinutes(195), start.plusMinutes(270))
    private val prefs = NotificationPrefs()
    private val logs = mutableMapOf<String, ExecutionLog>()

    private fun tap(action: CheckInAction, at: LocalDateTime, p: NotificationPrefs = prefs, nextBlock: CheckInBlock? = null): CheckInEffects =
        CheckInResponse.handle(action, block, nextBlock, at, p) { logs[CheckInLog.idFor(it)] }
            .also { e -> e.logs.forEach { logs[it.id] = it } }

    private fun log(b: CheckInBlock = block) = logs.getValue(CheckInLog.idFor(b))

    @Test
    fun `Start logs actualStart and nothing else`() {
        val e = tap(CheckInAction.START, start.plusMinutes(2))
        assertEquals(start.plusMinutes(2), log().actualStart)
        assertEquals(ExecutionOutcome.STARTED, log().outcome)
        assertNull(e.replan)
        assertNull(e.askFocusFor)
    }

    @Test
    fun `a second Start keeps the first time`() {
        tap(CheckInAction.START, start)
        tap(CheckInAction.START, start.plusMinutes(10))
        assertEquals(start, log().actualStart)
    }

    @Test
    fun `Snooze asks for the ping again after the snooze minutes`() {
        val e = tap(CheckInAction.SNOOZE, start)
        assertEquals(start.plusMinutes(10), e.snoozeUntil)
        assertEquals(emptyList(), e.logs)
        assertEquals(start.plusMinutes(5), tap(CheckInAction.SNOOZE, start, NotificationPrefs(snoozeMin = 5)).snoozeUntil)
    }

    @Test
    fun `Skip logs skipped`() {
        tap(CheckInAction.SKIP, start)
        assertEquals(ExecutionOutcome.SKIPPED, log().outcome)
    }

    @Test
    fun `Done logs actualEnd and asks for focus`() {
        tap(CheckInAction.START, start)
        val e = tap(CheckInAction.DONE, block.plannedEnd.plusMinutes(3))
        assertEquals(start, log().actualStart)
        assertEquals(block.plannedEnd.plusMinutes(3), log().actualEnd)
        assertEquals(ExecutionOutcome.DONE, log().outcome)
        assertEquals(block, e.askFocusFor)
        assertNull(e.replan)
    }

    @Test
    fun `Done without Start leaves the start unknown`() {
        tap(CheckInAction.DONE, block.plannedEnd)
        assertNull(log().actualStart)
    }

    @Test
    fun `the focus question is a setting`() {
        assertNull(tap(CheckInAction.DONE, block.plannedEnd, NotificationPrefs(askFocusOnDone = false)).askFocusFor)
    }

    @Test
    fun `Done, start next closes one block and opens the next`() {
        val e = tap(CheckInAction.DONE_START_NEXT, block.plannedEnd, nextBlock = next)
        assertEquals(ExecutionOutcome.DONE, log().outcome)
        assertEquals(ExecutionOutcome.STARTED, log(next).outcome)
        assertEquals(block.plannedEnd, log(next).actualStart)
        assertEquals(block, e.askFocusFor)
    }

    @Test
    fun `Need 30 more proposes an overrun replan and writes nothing else`() {
        val e = tap(CheckInAction.EXTEND, block.plannedEnd)
        assertEquals(ReplanReason.Overrun(block.ref, 30), e.replan)
        assertEquals(ExecutionOutcome.EXTENDED, log().outcome)
        assertNull(log().actualEnd)
    }

    @Test
    fun `Stop here logs the end now and proposes a replan of the rest`() {
        val at = block.plannedEnd.minusMinutes(40)
        val e = tap(CheckInAction.STOP, at)
        assertEquals(ReplanReason.StoppedEarly(block.ref, at), e.replan)
        assertEquals(at, log().actualEnd)
        assertEquals(ExecutionOutcome.STOPPED, log().outcome)
    }

    @Test
    fun `focus is 1 to 5`() {
        assertEquals(4, CheckInLog.focus(null, block, 4).focus1to5)
        assertFailsWith<IllegalArgumentException> { CheckInLog.focus(null, block, 0) }
        assertFailsWith<IllegalArgumentException> { CheckInLog.focus(null, block, 6) }
    }

    @Test
    fun `the log is keyed by event and planned start, so the store finds it again`() = runBlocking {
        val store = InMemoryExecutionLogStore()
        store.upsert(CheckInLog.started(null, block, start))
        val found = store.forEvent(block.ref, block.plannedStart)
        assertEquals(CheckInLog.idFor(block), found?.id)
        assertEquals("PintOS", found?.title)
        assertEquals("pintos-tue", found?.taskId)
    }

    @Test
    fun `a replan from a notification goes through ProposalPort and only proposes`() = runBlocking {
        val store = InMemoryChangeSetStore()
        val port = FakeProposalPort(store) { Instant.EPOCH }
        val reason = tap(CheckInAction.EXTEND, block.plannedEnd).replan!!
        val outcome = port.replan(reason, ChangeSource.NOTIFICATION)
        assertEquals(listOf<Any>(reason), port.calls)
        val proposed = outcome as ProposalOutcome.Proposed
        assertEquals(ChangeSource.NOTIFICATION, proposed.changeSet.source)
        assertEquals(app.planner.engine.changeset.ChangeStatus.PROPOSED, store.get(proposed.changeSet.id)?.status)
        val notice = CheckInTexts.replanNotice(outcome)
        assertEquals("Review change", notice.title)
        assertEquals(OpenTarget.APPROVALS, notice.open)
    }
}
