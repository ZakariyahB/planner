package app.planner.engine.checkin

import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.ExecutionOutcome
import java.time.LocalDateTime

/**
 * What a check-in action does to the block's [ExecutionLog]. Logging is not a calendar edit and needs no approval.
 * Each function takes the existing log (or null) and returns the one to save; times the user did not tell us stay
 * null rather than being guessed (Done without Start keeps actualStart unknown).
 */
object CheckInLog {
    fun idFor(block: CheckInBlock): String = "log:${block.key}:${block.plannedStart}"

    fun fresh(block: CheckInBlock): ExecutionLog = ExecutionLog(
        id = idFor(block),
        taskId = block.taskId,
        eventRef = block.ref,
        title = block.title,
        plannedStart = block.plannedStart,
        plannedEnd = block.plannedEnd,
    )

    private fun base(existing: ExecutionLog?, block: CheckInBlock) = existing ?: fresh(block)

    /** Start: logs actualStart. A second tap keeps the first time. */
    fun started(existing: ExecutionLog?, block: CheckInBlock, at: LocalDateTime): ExecutionLog {
        val log = base(existing, block)
        return log.copy(actualStart = log.actualStart ?: at, outcome = ExecutionOutcome.STARTED)
    }

    fun skipped(existing: ExecutionLog?, block: CheckInBlock): ExecutionLog =
        base(existing, block).copy(outcome = ExecutionOutcome.SKIPPED)

    /** Done: logs actualEnd. */
    fun done(existing: ExecutionLog?, block: CheckInBlock, at: LocalDateTime): ExecutionLog =
        base(existing, block).copy(actualEnd = at, outcome = ExecutionOutcome.DONE)

    /** Need 30 more: still going; the replan proposal is separate and needs approval. */
    fun extended(existing: ExecutionLog?, block: CheckInBlock): ExecutionLog =
        base(existing, block).copy(outcome = ExecutionOutcome.EXTENDED)

    /** Stop here: logs actualEnd now; what is left goes to the replan proposal. */
    fun stopped(existing: ExecutionLog?, block: CheckInBlock, at: LocalDateTime): ExecutionLog =
        base(existing, block).copy(actualEnd = at, outcome = ExecutionOutcome.STOPPED)

    /** The optional 1-5 focus tap after Done. Out-of-range ratings are rejected. */
    fun focus(existing: ExecutionLog?, block: CheckInBlock, rating: Int): ExecutionLog {
        require(rating in 1..5) { "focus is 1 to 5, got $rating" }
        return base(existing, block).copy(focus1to5 = rating)
    }
}
