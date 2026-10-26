package app.planner.ai

import app.planner.scheduler.ChangeSize
import app.planner.scheduler.Validation

enum class ProposalStatus { PENDING, APPLIED, REJECTED }

data class Proposal(
    val id: String,
    val summary: String,
    val ops: List<AiOp>,
    val validation: Validation,
    val size: ChangeSize,
    val status: ProposalStatus = ProposalStatus.PENDING,
)

sealed interface ApplyDecision {
    /** May be applied now. */
    data object Allowed : ApplyDecision

    /** Valid but big: only after the user taps Approve. */
    data object NeedsApproval : ApplyDecision

    /** Breaks a rule; never applied, whoever approves. */
    data class Invalid(val reasons: List<String>) : ApplyDecision

    data object Unknown : ApplyDecision

    data object AlreadyDone : ApplyDecision
}

/**
 * The model's proposals and the autonomy rule: a small valid change applies straight away (with Undo),
 * anything that drops a task, moves it to another day or shifts more than an hour waits for the user.
 */
class ProposalBook {
    private val proposals = linkedMapOf<String, Proposal>()
    private var next = 1

    fun add(summary: String, ops: List<AiOp>, validation: Validation): Proposal {
        require(ops.isNotEmpty()) { "a proposal needs at least one change" }
        val size = if (ops.any { it is AiOp.DropTask }) ChangeSize.BIG else validation.size
        val proposal = Proposal("p${next++}", summary, ops, validation, size)
        proposals[proposal.id] = proposal
        return proposal
    }

    fun get(id: String): Proposal? = proposals[id]

    fun pending(): List<Proposal> = proposals.values.filter { it.status == ProposalStatus.PENDING }

    fun decide(id: String, approvedByUser: Boolean): ApplyDecision {
        val p = proposals[id] ?: return ApplyDecision.Unknown
        if (p.status != ProposalStatus.PENDING) return ApplyDecision.AlreadyDone
        if (!p.validation.ok) {
            return ApplyDecision.Invalid(p.validation.violations.filter { it.hard }.map { it.message }.distinct())
        }
        return if (p.size == ChangeSize.SMALL || approvedByUser) ApplyDecision.Allowed else ApplyDecision.NeedsApproval
    }

    fun markApplied(id: String) = setStatus(id, ProposalStatus.APPLIED)

    fun reject(id: String) = setStatus(id, ProposalStatus.REJECTED)

    private fun setStatus(id: String, status: ProposalStatus) {
        proposals[id]?.let { proposals[id] = it.copy(status = status) }
    }
}
