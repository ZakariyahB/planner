package app.planner.gcal

import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.fakes.FakeProposalPort
import app.planner.engine.fakes.InMemoryChangeSetStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalGateTest {
    private val sample = FakeProposalPort(InMemoryChangeSetStore()).sample(ChangeSource.USER, "test")

    @Test
    fun `only approved changes pass the gate`() {
        val approved = ApprovalGate.approve(sample, mapOf("c1" to true, "c2" to false))
        assertEquals(listOf("c1"), approved.approved.map { it.id })
        assertTrue(approved.approved.all { it.status == ChangeStatus.APPROVED })
    }

    @Test
    fun `missing decisions count as rejected`() {
        assertTrue(ApprovalGate.approve(sample, emptyMap()).approved.isEmpty())
    }

    @Test
    fun `approve all takes every proposed change`() {
        assertEquals(2, ApprovalGate.approveAll(sample).approved.size)
    }
}
