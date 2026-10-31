package app.planner.engine.checkin

import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.NotificationPrefs
import app.planner.engine.ports.ReplanReason
import java.time.LocalDateTime

/**
 * What tapping an action on a check-in does, as data. :app carries the effects out: saves [logs] to the
 * ExecutionLogStore, sets the snooze alarm, asks ProposalPort.replan for [replan] (which only proposes; the user
 * approves on the approval screen), and posts the focus question.
 */
data class CheckInEffects(
    val logs: List<ExecutionLog> = emptyList(),
    /** Ask the engine for a replan ChangeSet with source NOTIFICATION. Never a calendar write. */
    val replan: ReplanReason? = null,
    /** Show the start ping again at this time, under [CheckIn.snoozeId]. */
    val snoozeUntil: LocalDateTime? = null,
    /** Post the optional 1-5 focus question for this block. */
    val askFocusFor: CheckInBlock? = null,
)

object CheckInResponse {
    /**
     * [existing] looks up the current log of a block (null if none). [next] is the block a "Next up" ping is about.
     */
    fun handle(
        action: CheckInAction,
        block: CheckInBlock,
        next: CheckInBlock?,
        now: LocalDateTime,
        prefs: NotificationPrefs,
        existing: (CheckInBlock) -> ExecutionLog?,
    ): CheckInEffects = when (action) {
        CheckInAction.START -> CheckInEffects(logs = listOf(CheckInLog.started(existing(block), block, now)))
        CheckInAction.SNOOZE -> CheckInEffects(snoozeUntil = now.plusMinutes(prefs.snoozeMin.toLong()))
        CheckInAction.SKIP -> CheckInEffects(logs = listOf(CheckInLog.skipped(existing(block), block)))
        CheckInAction.DONE -> CheckInEffects(
            logs = listOf(CheckInLog.done(existing(block), block, now)),
            askFocusFor = block.takeIf { prefs.askFocusOnDone },
        )
        CheckInAction.DONE_START_NEXT -> CheckInEffects(
            logs = listOfNotNull(
                CheckInLog.done(existing(block), block, now),
                next?.let { CheckInLog.started(existing(it), it, now) },
            ),
            askFocusFor = block.takeIf { prefs.askFocusOnDone },
        )
        CheckInAction.EXTEND -> CheckInEffects(
            logs = listOf(CheckInLog.extended(existing(block), block)),
            replan = ReplanReason.Overrun(block.ref, prefs.extendMin),
        )
        CheckInAction.STOP -> CheckInEffects(
            logs = listOf(CheckInLog.stopped(existing(block), block, now)),
            replan = ReplanReason.StoppedEarly(block.ref, now),
        )
    }
}
