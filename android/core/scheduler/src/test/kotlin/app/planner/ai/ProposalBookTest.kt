package app.planner.ai

import app.planner.scheduler.ChangeSize
import app.planner.scheduler.Validation
import app.planner.scheduler.Violation
import app.planner.scheduler.ViolationKind
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProposalBookTest {
    private val book = ProposalBook()
    private val move = AiOp.MoveBlock(1, LocalDate.of(2026, 10, 28), LocalTime.of(15, 0), LocalTime.of(16, 0))
    private val ok = Validation(emptyList(), ChangeSize.SMALL)

    @Test
    fun `a small valid change may be applied without asking`() {
        val p = book.add("Shift OS an hour", listOf(move), ok)
        assertEquals(ChangeSize.SMALL, p.size)
        assertEquals(ApplyDecision.Allowed, book.decide(p.id, approvedByUser = false))
    }

    @Test
    fun `a big change needs the user's approval`() {
        val p = book.add("Move CW to Sunday", listOf(move), Validation(emptyList(), ChangeSize.BIG))
        assertEquals(ApplyDecision.NeedsApproval, book.decide(p.id, approvedByUser = false))
        assertEquals(ApplyDecision.Allowed, book.decide(p.id, approvedByUser = true))
    }

    @Test
    fun `dropping a task is always big, even with no block changes`() {
        val p = book.add("Drop the reading", listOf(AiOp.DropTask(4)), ok)
        assertEquals(ChangeSize.BIG, p.size)
        assertEquals(ApplyDecision.NeedsApproval, book.decide(p.id, approvedByUser = false))
    }

    @Test
    fun `an invalid change can never be applied, even if approved`() {
        val bad = Validation(listOf(Violation(1, ViolationKind.OVERLAP_FIXED, "That clashes with a fixed commitment")), ChangeSize.SMALL)
        val p = book.add("Bad idea", listOf(move), bad)
        val decision = book.decide(p.id, approvedByUser = true)
        assertIs<ApplyDecision.Invalid>(decision)
        assertEquals(listOf("That clashes with a fixed commitment"), decision.reasons)
    }

    @Test
    fun `a warning alone does not make a change invalid`() {
        val warn = Validation(listOf(Violation(1, ViolationKind.NO_BUFFER, "Tight", hard = false)), ChangeSize.SMALL)
        assertEquals(ApplyDecision.Allowed, book.decide(book.add("Tight", listOf(move), warn).id, false))
    }

    @Test
    fun `a proposal can only be applied once`() {
        val p = book.add("Shift", listOf(move), ok)
        book.markApplied(p.id)
        assertEquals(ApplyDecision.AlreadyDone, book.decide(p.id, true))
        assertEquals(ProposalStatus.APPLIED, book.get(p.id)!!.status)
    }

    @Test
    fun `a rejected proposal cannot be applied`() {
        val p = book.add("Shift", listOf(move), ok)
        book.reject(p.id)
        assertEquals(ApplyDecision.AlreadyDone, book.decide(p.id, true))
    }

    @Test
    fun `an unknown id is reported`() {
        assertEquals(ApplyDecision.Unknown, book.decide("p99", true))
    }

    @Test
    fun `ids are unique and pending proposals are listed in order`() {
        val a = book.add("A", listOf(move), ok)
        val b = book.add("B", listOf(move), ok)
        assertTrue(a.id != b.id)
        book.markApplied(a.id)
        assertEquals(listOf(b.id), book.pending().map { it.id })
    }

    @Test
    fun `an empty proposal is refused`() {
        assertFailsWith<IllegalArgumentException> { book.add("Nothing", emptyList(), ok) }
    }
}
