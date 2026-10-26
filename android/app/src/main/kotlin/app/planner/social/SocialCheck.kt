package app.planner.social

import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** One pass of the 15-minute job: refresh today's minutes, then send whatever nudge or summary is due. */
@Singleton
class SocialCheck @Inject constructor(
    private val tracker: SocialUsageTracker,
    private val social: SocialRepository,
    private val settings: SocialSettings,
    private val notifier: SocialNotifier,
    private val clock: Clock,
) {
    suspend fun run() {
        val today = tracker.refresh() ?: return // no usage access: nothing to read or say
        if (settings.packages().isEmpty()) return // no apps selected: a summary of nothing is noise

        val budget = social.budget.first()
        val canNotify = notifier.canNotify()
        val decision = SocialNudgeState.decide(
            now = LocalDateTime.now(clock),
            usedMin = today.minutes,
            budget = budget,
            sent = settings.sent(),
            nudgesEnabled = canNotify && settings.nudgesOn.first(),
            summaryEnabled = canNotify && settings.summaryOn.first(),
        )
        val budgetMin = budget.minutesFor(today.date)
        decision.nudge?.let { notifier.nudge(SocialNudges.message(it, today.minutes, budgetMin)) }
        if (decision.summary) notifier.summary(SocialNudges.summary(today.minutes, budgetMin))
        settings.saveSent(decision.sent)
    }
}
