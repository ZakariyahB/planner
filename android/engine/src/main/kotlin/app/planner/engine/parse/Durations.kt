package app.planner.engine.parse

/**
 * Durations as the user writes them in template titles: `3h`, `1h30`, `1h 30m`, `45m`, `90 min`, `1.5h`, `2 hours`.
 * Formatting goes the other way, in the same style (`2h`, `1h30`, `45m`), for event descriptions.
 */
object Durations {
    private const val H = """(?:h|hr|hrs|hour|hours)"""
    private const val M = """(?:m|min|mins|minute|minutes)"""
    private val HOURS = Regex("""^(\d+(?:\.\d+)?)\s*$H$""", RegexOption.IGNORE_CASE)
    private val HOURS_MINUTES = Regex("""^(\d+)\s*$H\s*(\d{1,2})\s*$M?$""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""^(\d+)\s*$M$""", RegexOption.IGNORE_CASE)

    /** Shortest and longest durations accepted from a title; anything outside is treated as a typo. */
    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 12 * 60

    /** Minutes, or null when [text] is not a duration or is outside [MIN_MINUTES]..[MAX_MINUTES]. */
    fun parse(text: String): Int? {
        val t = text.trim()
        val minutes = HOURS.matchEntire(t)?.let { Math.round(it.groupValues[1].toDouble() * 60).toInt() }
            ?: HOURS_MINUTES.matchEntire(t)?.let { m ->
                val mins = m.groupValues[2].toInt()
                if (mins >= 60) null else m.groupValues[1].toInt() * 60 + mins
            }
            ?: MINUTES.matchEntire(t)?.groupValues?.get(1)?.toInt()
            ?: return null
        return minutes.takeIf { it in MIN_MINUTES..MAX_MINUTES }
    }

    /** `120` to `2h`, `90` to `1h30`, `45` to `45m`, `185` to `3h05`. */
    fun format(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h" + m.toString().padStart(2, '0')
        }
    }
}
