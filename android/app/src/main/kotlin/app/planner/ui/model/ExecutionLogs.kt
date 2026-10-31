package app.planner.ui.model

import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.ports.TodayBlock
import java.time.LocalDateTime

/**
 * What the Start / Done / Skip buttons on Today write to the execution log. Logging is not a calendar edit and
 * needs no approval. The log id is the block's stable key, so tapping twice updates one record.
 */
object ExecutionLogs {
    fun base(block: TodayBlock, existing: ExecutionLog?): ExecutionLog = existing ?: ExecutionLog(
        id = DayTimelines.blockKey(block),
        taskId = block.taskId,
        eventRef = block.ref,
        title = DayTimelines.blockTitle(block),
        plannedStart = block.start,
        plannedEnd = block.end,
    )

    fun start(block: TodayBlock, existing: ExecutionLog?, now: LocalDateTime): ExecutionLog =
        base(block, existing).let { it.copy(actualStart = it.actualStart ?: now, outcome = ExecutionOutcome.STARTED) }

    /** Done keeps an earlier start; if none was logged the start stays unknown rather than guessed. */
    fun done(block: TodayBlock, existing: ExecutionLog?, now: LocalDateTime): ExecutionLog =
        base(block, existing).copy(actualEnd = now, outcome = ExecutionOutcome.DONE)

    fun stopped(block: TodayBlock, existing: ExecutionLog?, now: LocalDateTime): ExecutionLog =
        base(block, existing).copy(actualEnd = now, outcome = ExecutionOutcome.STOPPED)

    fun extended(block: TodayBlock, existing: ExecutionLog?, now: LocalDateTime): ExecutionLog =
        base(block, existing).let { it.copy(actualStart = it.actualStart ?: block.start.takeIf { s -> !now.isBefore(s) }, outcome = ExecutionOutcome.EXTENDED) }

    fun skip(block: TodayBlock, existing: ExecutionLog?): ExecutionLog =
        base(block, existing).copy(outcome = ExecutionOutcome.SKIPPED)

    fun focus(log: ExecutionLog, rating: Int): ExecutionLog = log.copy(focus1to5 = rating.coerceIn(1, 5))
}
