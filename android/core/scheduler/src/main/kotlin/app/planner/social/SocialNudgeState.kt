package app.planner.social

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** What has already been sent on [date]; a new day starts from an empty one. */
data class SocialSent(val date: LocalDate, val nudges: Set<Nudge> = emptySet(), val summarySent: Boolean = false) {
    /** `date|HALF,MOST|1`: small enough to keep as one setting. */
    fun encode(): String = "$date|${nudges.sortedBy { it.percent }.joinToString(",") { it.name }}|${if (summarySent) 1 else 0}"

    companion object {
        /** Null when nothing was saved or the text can't be read, which just means nothing counts as sent. */
        fun decode(text: String?): SocialSent? {
            val parts = text?.split('|') ?: return null
            if (parts.size != 3) return null
            val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
            val nudges = parts[1].split(',').mapNotNull { name -> Nudge.entries.firstOrNull { it.name == name } }.toSet()
            return SocialSent(date, nudges, summarySent = parts[2] == "1")
        }
    }
}

/** What to notify about right now, and the markers to save once it has been sent. */
data class SocialDecision(val nudge: Nudge?, val summary: Boolean, val sent: SocialSent)

object SocialNudgeState {
    val SUMMARY_TIME: LocalTime = LocalTime.of(21, 30)

    /**
     * Never repeats a nudge or the summary within a day and starts over on a new date. A turned-off kind is
     * neither sent nor marked, so turning it back on later still delivers what is due.
     */
    fun decide(
        now: LocalDateTime,
        usedMin: Int,
        budget: SocialBudget,
        sent: SocialSent?,
        nudgesEnabled: Boolean = true,
        summaryEnabled: Boolean = true,
    ): SocialDecision {
        val today = now.toLocalDate()
        val before = sent?.takeIf { it.date == today } ?: SocialSent(today)

        val nudge = if (nudgesEnabled) SocialNudges.due(usedMin, budget.minutesFor(today), before.nudges) else null
        val summary = summaryEnabled && !before.summarySent && now.toLocalTime() >= SUMMARY_TIME

        val after = before.copy(
            nudges = if (nudge != null) SocialNudges.markSent(nudge, before.nudges) else before.nudges,
            summarySent = before.summarySent || summary,
        )
        return SocialDecision(nudge, summary, after)
    }
}
