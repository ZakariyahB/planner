package app.planner.gcal.plan

import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.Placement
import app.planner.engine.model.Prayer
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.model.Tier
import app.planner.engine.parse.Durations
import app.planner.engine.parse.Names
import java.time.format.DateTimeFormatter

/**
 * newplan 16: event descriptions on app blocks, e.g.
 * `Task: 2h. Recording available from 14:00. Includes 15 min for Asr. Extended hours (after 18:00). (Sat prayer time interpolated)`.
 * Whatever the user wrote in the description themselves is kept below a blank line.
 */
object Descriptions {
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    const val PREFIX = "Task:"

    fun forBlock(task: Task, p: Placement, settings: Settings): String {
        val parts = mutableListOf<String>()
        parts += if (p.chunkCount > 1) "$PREFIX ${Durations.format(task.durationMin)}, part ${p.chunkIndex} of ${p.chunkCount}."
        else "$PREFIX ${Durations.format(task.durationMin)}."
        task.earliestStart?.let {
            parts += if (task.category == CalendarCategory.LECTURES) "Recording available from ${it.format(HM)}." else "Not before ${it.format(HM)}."
        }
        task.afterPrayer?.let { parts += "After ${prayerName(it)}." }
        bufferLine(p.prayerBufferMin, p.coveredPrayers)?.let { parts += it }
        tierLine(p.tier, settings)?.let { parts += it }
        if (p.interpolatedPrayer) parts += "(${Names.shortName(p.start.dayOfWeek)} prayer time interpolated)"
        return parts.joinToString(" ")
    }

    /** "Includes 15 min for Dhuhr" or "Includes 30 min for Dhuhr and Asr", or null without a buffer. */
    fun bufferLine(bufferMin: Int, covered: List<Prayer>): String? {
        if (bufferMin <= 0 || covered.isEmpty()) return null
        val names = covered.map(::prayerName)
        val joined = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        return "Includes $bufferMin min for $joined."
    }

    fun tierLine(tier: Tier, settings: Settings): String? = when (tier) {
        Tier.NORMAL -> null
        Tier.REST -> "Uses rest zone."
        Tier.EXTENDED -> "Extended hours (after ${settings.normalEnd.format(HM)})."
        Tier.OVER_SOFT_LIMIT -> "Over the soft limit (after ${settings.extendedEnd.format(HM)})."
    }

    /** The planner's text on top, the user's own text (anything that is not an earlier planner line) kept below. */
    fun merge(plannerText: String, previous: String?): String {
        val own = userPart(previous)
        return if (own.isNullOrBlank()) plannerText else "$plannerText\n\n$own"
    }

    fun userPart(previous: String?): String? {
        if (previous.isNullOrBlank()) return null
        if (!previous.trimStart().startsWith(PREFIX)) return previous.trim()
        return previous.substringAfter("\n\n", "").trim().ifEmpty { null }
    }

    fun prayerName(p: Prayer): String = p.name.lowercase().replaceFirstChar { it.uppercase() }
}
