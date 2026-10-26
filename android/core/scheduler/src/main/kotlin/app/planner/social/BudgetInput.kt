package app.planner.social

/** Turns the hours and minutes the user types in Settings into a budget in minutes. */
object BudgetInput {
    private const val MAX_MINUTES = 24 * 60 - 1

    /** Null for anything that isn't a plain number, minutes over 59, or a budget of zero or a full day. */
    fun toMinutes(hours: String, minutes: String): Int? {
        val h = number(hours) ?: return null
        val m = number(minutes) ?: return null
        if (m > 59 || h > 23) return null
        return (h * 60 + m).takeIf { it in 1..MAX_MINUTES }
    }

    fun split(totalMinutes: Int): Pair<Int, Int> = totalMinutes / 60 to totalMinutes % 60

    private fun number(text: String): Int? {
        val t = text.trim()
        return when {
            t.isEmpty() -> 0
            t.length > 4 || !t.all { it in '0'..'9' } -> null
            else -> t.toInt()
        }
    }
}
