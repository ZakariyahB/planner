package app.planner.social

import java.time.DayOfWeek
import java.time.LocalDate

/** Daily limits on social-media time: nudges only, nothing is ever blocked. */
data class SocialBudget(val weekdayMin: Int = 120, val weekendMin: Int = 180) {
    init {
        require(weekdayMin > 0 && weekendMin > 0) { "budgets must be positive" }
    }

    fun minutesFor(date: LocalDate): Int =
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) weekendMin else weekdayMin
}

enum class Nudge(val percent: Int) { HALF(50), MOST(80), FULL(100) }

object SocialNudges {
    fun percent(usedMin: Int, budgetMin: Int): Int = usedMin * 100 / budgetMin

    /** The highest threshold crossed that hasn't been sent yet, or null. A jump past two sends only the higher. */
    fun due(usedMin: Int, budgetMin: Int, alreadySent: Set<Nudge>): Nudge? =
        Nudge.entries.filter { usedMin * 100 >= it.percent * budgetMin && it !in alreadySent }.maxByOrNull { it.percent }

    /** Sending a nudge also settles the lower ones, so crossing 100% never replays the 50% message. */
    fun markSent(nudge: Nudge, alreadySent: Set<Nudge>): Set<Nudge> =
        alreadySent + Nudge.entries.filter { it.percent <= nudge.percent }

    fun message(nudge: Nudge, usedMin: Int, budgetMin: Int): String {
        val used = duration(usedMin)
        val total = duration(budgetMin)
        return when (nudge) {
            Nudge.HALF -> "Halfway through your social time: $used of $total."
            Nudge.MOST -> "$used of $total used, ${budgetMin - usedMin} minutes left."
            Nudge.FULL ->
                if (usedMin > budgetMin) "Social time used: $used of $total, ${usedMin - budgetMin} minutes over. Just a nudge."
                else "Social time used: $used of $total. Just a nudge, nothing is blocked."
        }
    }

    fun summary(usedMin: Int, budgetMin: Int): String {
        val used = duration(usedMin)
        return when {
            usedMin < budgetMin -> "Social time today: $used, ${budgetMin - usedMin} minutes under budget."
            usedMin == budgetMin -> "Social time today: $used, right on budget."
            else -> "Social time today: $used, ${usedMin - budgetMin} minutes over budget."
        }
    }

    fun duration(minutes: Int): String = "${minutes / 60}h ${"%02d".format(minutes % 60)}m"
}
