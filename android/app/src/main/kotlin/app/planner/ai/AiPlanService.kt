package app.planner.ai

import app.planner.data.TaskRepository
import app.planner.model.TaskStatus
import app.planner.plan.MoveResult
import app.planner.plan.PlanMessage
import app.planner.plan.PlanRepository
import app.planner.scheduler.ChangeSize
import app.planner.scheduler.Validation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Holds the model's proposals and applies them under the autonomy rule: a small valid change goes straight
 * in (and the user gets an Undo), anything bigger waits for the user to tap Approve.
 */
@Singleton
class AiPlanService @Inject constructor(
    private val plan: PlanRepository,
    private val tasks: TaskRepository,
) {
    private val book = ProposalBook()

    private val _pending = MutableStateFlow<List<Proposal>>(emptyList())

    /** Proposals waiting for the user, shown as cards in the chat. */
    val pending: StateFlow<List<Proposal>> = _pending

    private val _announcements = MutableSharedFlow<PlanMessage>(extraBufferCapacity = 8)

    /** Changes made on the user's behalf, so the app can show a snackbar with Undo. */
    val announcements: SharedFlow<PlanMessage> = _announcements.asSharedFlow()

    fun announce(message: PlanMessage) {
        _announcements.tryEmit(message)
    }

    suspend fun propose(summary: String, ops: JsonArray?): ToolOutcome {
        val parsed = AiOps.parse(ops)
        if (!parsed.ok) {
            return error("Fix these and propose again: ${parsed.errors.joinToString("; ")}")
        }
        val changeSet = AiOps.toChangeSet(parsed.ops)
        val validation = if (changeSet.ops.isEmpty()) Validation(emptyList(), ChangeSize.SMALL) else plan.check(changeSet)
        val proposal = book.add(summary.ifBlank { "Change the plan" }, parsed.ops, validation)
        refresh()
        return ToolOutcome(
            buildJsonObject {
                put("proposal_id", proposal.id)
                put("valid", validation.ok)
                put("size", proposal.size.name.lowercase())
                put("needs_approval", proposal.size == ChangeSize.BIG)
                put("problems", buildJsonArray { validation.violations.filter { it.hard }.forEach { add(JsonPrimitive(it.message)) } })
                put("warnings", buildJsonArray { validation.violations.filterNot { it.hard }.forEach { add(JsonPrimitive(it.message)) } })
            }.toString(),
        )
    }

    /** From the assistant: only goes through if it is small, or the user has already approved it. */
    suspend fun apply(id: String): ToolOutcome = decideAndApply(id, approvedByUser = false)

    /** From the Approve button. */
    suspend fun approve(id: String): ToolOutcome = decideAndApply(id, approvedByUser = true)

    fun reject(id: String) {
        book.reject(id)
        refresh()
    }

    private suspend fun decideAndApply(id: String, approvedByUser: Boolean): ToolOutcome {
        val proposal = book.get(id)
        return when (val decision = book.decide(id, approvedByUser)) {
            ApplyDecision.Unknown -> error("No proposal with id $id")
            ApplyDecision.AlreadyDone -> error("That proposal has already been dealt with")
            is ApplyDecision.Invalid -> error("That change breaks a rule: ${decision.reasons.joinToString("; ")}")
            ApplyDecision.NeedsApproval -> ToolOutcome(
                buildJsonObject {
                    put("status", "needs_approval")
                    put("message", "This is a big change. The user has to tap Approve on the proposal card; tell them what you suggest and wait.")
                }.toString(),
            )
            ApplyDecision.Allowed -> execute(proposal!!)
        }
    }

    private suspend fun execute(proposal: Proposal): ToolOutcome {
        var changeId: Long? = null
        val blockChanges = AiOps.toChangeSet(proposal.ops)
        if (blockChanges.ops.isNotEmpty()) {
            when (val result = plan.applyChanges(blockChanges, proposal.summary)) {
                is MoveResult.Rejected -> return error("The plan changed and this no longer fits: ${result.reasons.joinToString("; ")}")
                is MoveResult.Applied -> changeId = result.changeId
            }
        }
        val dropped = AiOps.droppedTaskIds(proposal.ops)
        if (dropped.isNotEmpty()) {
            dropped.forEach { taskId ->
                plan.forgetBlocks(taskId)
                tasks.setStatus(taskId, TaskStatus.DROPPED)
            }
            plan.replan()
        }
        book.markApplied(proposal.id)
        refresh()
        announce(PlanMessage(proposal.summary, changeId))
        return ToolOutcome(
            buildJsonObject {
                put("status", "applied")
                changeId?.let { put("change_id", it) }
                put("message", "Applied. The user sees an Undo for a few seconds.")
            }.toString(),
        )
    }

    private fun refresh() {
        _pending.value = book.pending()
    }

    private fun error(message: String) = ToolOutcome(buildJsonObject { put("error", message) }.toString(), isError = true)
}
