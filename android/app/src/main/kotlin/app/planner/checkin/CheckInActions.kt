package app.planner.checkin

import android.util.Log
import app.planner.data.SettingsRepository
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.checkin.AlarmPayload
import app.planner.engine.checkin.CheckIn
import app.planner.engine.checkin.CheckInAction
import app.planner.engine.checkin.CheckInBlock
import app.planner.engine.checkin.CheckInLog
import app.planner.engine.checkin.CheckInResponse
import app.planner.engine.checkin.CheckInTexts
import app.planner.engine.checkin.stillDue
import app.planner.engine.model.TaskStatus
import app.planner.engine.nudge.FajrInput
import app.planner.engine.nudge.Nudge
import app.planner.engine.nudge.NudgeContext
import app.planner.engine.nudge.NudgeKind
import app.planner.engine.nudge.NudgeTexts
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.ExecutionLogStore
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.ProposalOutcome
import app.planner.engine.ports.ProposalPort
import app.planner.engine.ports.ReplanReason
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.TaskStore
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * What happens when a check-in alarm fires or an action is tapped. The decisions are the engine's
 * ([CheckInResponse], [NudgeTexts]); this class only reads and writes the stores and posts notifications.
 *
 * Nothing here writes to Google Calendar. "Need 30 more" and "Stop here" call [ProposalPort.replan], which saves a
 * ChangeSet for the approval screen and stops there; the "Review change" notification opens that screen.
 */
@Singleton
class CheckInActions @Inject constructor(
    private val settings: SettingsStore,
    private val logs: ExecutionLogStore,
    private val tasks: TaskStore,
    private val changeSets: ChangeSetStore,
    private val queries: Provider<PlannerQueries>,
    private val proposals: Provider<ProposalPort>,
    private val legacySettings: SettingsRepository,
    private val notifier: CheckInNotifier,
    private val scheduler: CheckInScheduler,
    private val clock: Clock,
) {
    private suspend fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(settings.current().zone))

    private suspend fun logOf(block: CheckInBlock) = logs.forEvent(block.ref, block.plannedStart)

    /** An alarm went off. */
    suspend fun onAlarm(payload: AlarmPayload) {
        when (payload) {
            is AlarmPayload.CheckInAlarm -> {
                val s = settings.current()
                val checkIn = payload.checkIn
                if (!s.notifications.checkInsEnabled) return
                val logsNow = mapOf(checkIn.block to logOf(checkIn.block))
                if (!checkIn.stillDue { logsNow[it] }) return
                notifier.postCheckIn(checkIn, s.notifications, s.prayerSlotMin)
            }
            is AlarmPayload.NudgeAlarm -> {
                try {
                    val ctx = try {
                        context(payload.nudge)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "Prompt context unavailable; posting without it", e)
                        NudgeContext()
                    }
                    NudgeTexts.forNudge(payload.nudge, ctx)?.let { notifier.postPrompt(payload.nudge, it) }
                } finally {
                    scheduler.reschedule() // sets the next occurrence
                }
            }
        }
    }

    /** A button on a check-in. Returns the replan to run in the background, if any. */
    suspend fun onAction(action: CheckInAction, checkIn: CheckIn): ReplanReason? {
        val s = settings.current()
        val now = now()
        val known = mutableMapOf<CheckInBlock, app.planner.engine.model.ExecutionLog?>()
        for (b in listOfNotNull(checkIn.block, checkIn.next)) known[b] = logOf(b)
        val effects = CheckInResponse.handle(action, checkIn.block, checkIn.next, now, s.notifications) { known[it] }

        effects.logs.forEach { logs.upsert(it) }
        notifier.cancel(checkIn.block)
        effects.snoozeUntil?.let { scheduler.snooze(checkIn, it, s.zone) }
        effects.askFocusFor?.let { notifier.postFocus(it) }
        if (effects.logs.isNotEmpty()) scheduler.reschedule() // e.g. Done drops the block's remaining pings
        return effects.replan
    }

    /** Runs in a worker: asks the engine for a proposal and says so. Never applies anything. */
    suspend fun replan(reason: ReplanReason) {
        val outcome = try {
            proposals.get().replan(reason, ChangeSource.NOTIFICATION)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Replan from a check-in failed", e)
            ProposalOutcome.Rejected(listOf("The replan could not run. Open the app to try again."))
        }
        val changeSetId = (outcome as? ProposalOutcome.Proposed)?.changeSet?.id
        notifier.postReplanNotice(CheckInTexts.replanNotice(outcome), changeSetId)
    }

    suspend fun onFocus(block: CheckInBlock, rating: Int?) {
        notifier.cancel(block)
        if (rating == null || rating !in 1..5) return
        logs.upsert(CheckInLog.focus(logOf(block), block, rating))
    }

    /** The quick reply on the weekly Fajr prompt. */
    suspend fun onFajr(nudge: Nudge, reply: String?) {
        val time = FajrInput.parse(reply)
        if (time == null) {
            notifier.confirmPrompt(NudgeKind.WEEKLY_FAJR, "Could not read that time", "Try something like 5:40, or set it in Settings.")
            return
        }
        legacySettings.saveFajr(nudge.forDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), time)
        notifier.confirmPrompt(NudgeKind.WEEKLY_FAJR, "Saved: Fajr about ${CheckInTexts.hm(time)}", "Used for this week's sleep and wake coaching.")
    }

    private suspend fun context(nudge: Nudge): NudgeContext = when (nudge.kind) {
        NudgeKind.WEEKLY_FAJR -> {
            val monday = nudge.forDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            NudgeContext(fajrKnown = legacySettings.fajrWeeks.first()[monday] != null)
        }
        NudgeKind.NIGHT_BEFORE_REVIEW -> {
            val day = nudge.forDate
            val q = queries.get()
            val blocks = q.week(day).days.firstOrNull { it.date == day }?.blocks
                ?: q.week(day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).days.firstOrNull { it.date == day }?.blocks.orEmpty()
            NudgeContext(
                tomorrow = blocks,
                unscheduledTomorrow = tasks.all().count { it.assignedDay == day && it.status == TaskStatus.BACKLOG },
                pendingApprovals = changeSets.observePending().first().size,
            )
        }
        NudgeKind.SUNDAY_DEADLINES -> NudgeContext()
    }

    private companion object {
        const val TAG = "CheckInActions"
    }
}
