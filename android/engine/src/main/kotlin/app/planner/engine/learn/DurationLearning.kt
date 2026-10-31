package app.planner.engine.learn

import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.Task
import java.time.Duration
import kotlin.math.roundToInt

/**
 * Actual versus planned minutes for one group of blocks (one task title, or one category).
 *
 * A block counts as a sample when it was finished (outcome DONE or EXTENDED) and has an actual start and end after
 * it. Stopped and skipped blocks say nothing about how long the work takes, so they are only counted.
 */
data class DurationStats(
    /** Grouping key: the normalised title, or the category name. */
    val key: String,
    /** For people: the most recent title seen, or the category's calendar name. */
    val label: String,
    val samples: Int,
    val plannedMin: Int,
    val actualMin: Int,
    /** Median of actual / planned over the samples; robust to the odd block left running. Null without samples. */
    val medianRatio: Double?,
    val meanFocus: Double?,
    val stopped: Int,
    val skipped: Int,
) {
    val meanPlannedMin: Int? get() = if (samples == 0) null else (plannedMin.toDouble() / samples).roundToInt()
    val meanActualMin: Int? get() = if (samples == 0) null else (actualMin.toDouble() / samples).roundToInt()
}

data class LearningReport(
    /** Most-sampled first. */
    val byTitle: List<DurationStats>,
    val byCategory: Map<CalendarCategory, DurationStats>,
)

/** Pure helpers that turn the execution log into context for the assistant. Wall-clock minutes (a block across the clock change is off by an hour). */
object DurationLearning {
    private val CHUNK_LABEL = Regex("""\s*\(\d+/\d+\)\s*$""")
    private val SPACES = Regex("""\s+""")

    /** "PintOS (1/2)" and "pintos  (2/2)" are the same work. */
    fun normaliseTitle(title: String): String = title.replace(CHUNK_LABEL, "").trim().replace(SPACES, " ").lowercase()

    /** Category of a logged block: its task's category, else the category of the calendar its event lives in. */
    fun categoryResolver(
        tasks: List<Task>,
        categoryOfCalendar: (calendarId: String) -> CalendarCategory?,
    ): (ExecutionLog) -> CalendarCategory? {
        val byTask = tasks.associate { it.id to it.category }
        return { log -> log.taskId?.let(byTask::get) ?: log.eventRef?.let { categoryOfCalendar(it.calendarId) } }
    }

    fun report(logs: List<ExecutionLog>, categoryOf: (ExecutionLog) -> CalendarCategory?): LearningReport =
        LearningReport(byTitle(logs), byCategory(logs, categoryOf))

    fun byTitle(logs: List<ExecutionLog>): List<DurationStats> =
        logs.groupBy { normaliseTitle(it.title) }
            .filterKeys { it.isNotEmpty() }
            .map { (key, group) -> stats(key, group.maxBy { it.plannedStart }.title.replace(CHUNK_LABEL, "").trim(), group) }
            .sortedWith(compareByDescending<DurationStats> { it.samples }.thenBy { it.key })

    fun byCategory(logs: List<ExecutionLog>, categoryOf: (ExecutionLog) -> CalendarCategory?): Map<CalendarCategory, DurationStats> =
        logs.mapNotNull { log -> categoryOf(log)?.let { it to log } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap(compareBy { it.ordinal })
            .mapValues { (cat, group) -> stats(cat.name, cat.calendarName, group) }

    private fun isSample(log: ExecutionLog): Boolean {
        val s = log.actualStart ?: return false
        val e = log.actualEnd ?: return false
        return (log.outcome == ExecutionOutcome.DONE || log.outcome == ExecutionOutcome.EXTENDED) &&
            e > s && log.plannedEnd > log.plannedStart
    }

    private fun minutes(from: java.time.LocalDateTime, to: java.time.LocalDateTime): Int = Duration.between(from, to).toMinutes().toInt()

    private fun stats(key: String, label: String, group: List<ExecutionLog>): DurationStats {
        val samples = group.filter(::isSample)
        val planned = samples.map { minutes(it.plannedStart, it.plannedEnd) }
        val actual = samples.map { minutes(it.actualStart!!, it.actualEnd!!) }
        val ratios = planned.zip(actual).filter { it.first > 0 }.map { (p, a) -> a.toDouble() / p }.sorted()
        val focus = samples.mapNotNull { it.focus1to5 }
        return DurationStats(
            key = key,
            label = label,
            samples = samples.size,
            plannedMin = planned.sum(),
            actualMin = actual.sum(),
            medianRatio = median(ratios),
            meanFocus = if (focus.isEmpty()) null else focus.average(),
            stopped = group.count { it.outcome == ExecutionOutcome.STOPPED },
            skipped = group.count { it.outcome == ExecutionOutcome.SKIPPED },
        )
    }

    private fun median(sorted: List<Double>): Double? = when {
        sorted.isEmpty() -> null
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    /** 135 -> "2h15", 120 -> "2h", 45 -> "45m". */
    fun formatMinutes(min: Int): String = when {
        min < 60 -> "${min}m"
        min % 60 == 0 -> "${min / 60}h"
        else -> "${min / 60}h${(min % 60).toString().padStart(2, '0')}"
    }

    private fun line(s: DurationStats): String {
        val parts = mutableListOf<String>()
        if (s.samples > 0) {
            val pct = ((s.medianRatio!! - 1) * 100).roundToInt()
            val signed = if (pct >= 0) "+$pct%" else "$pct%"
            parts += "planned ${formatMinutes(s.meanPlannedMin!!)}, took ${formatMinutes(s.meanActualMin!!)} on average (typically $signed)"
            parts += "${s.samples} done"
        }
        if (s.stopped > 0) parts += "${s.stopped} stopped early"
        if (s.skipped > 0) parts += "${s.skipped} skipped"
        s.meanFocus?.let { parts += "focus %.1f/5".format(java.util.Locale.ROOT, it) }
        return "- ${s.label}: ${parts.joinToString(", ")}"
    }

    /**
     * A compact plain-text block for the assistant's system prompt. Titles need [minSamples] finished blocks to be
     * listed (one block is noise); categories are listed whenever they have any finished block.
     */
    fun summary(report: LearningReport, maxTitles: Int = 10, minSamples: Int = 2): String {
        val titles = report.byTitle.filter { it.samples >= minSamples }.take(maxTitles)
        val cats = report.byCategory.values.filter { it.samples > 0 }
        if (titles.isEmpty() && cats.isEmpty()) return "Actual vs planned: not enough finished blocks logged yet."
        return buildString {
            append("Actual vs planned (finished blocks):")
            if (titles.isNotEmpty()) {
                append("\nBy task:")
                titles.forEach { append('\n').append(line(it)) }
            }
            if (cats.isNotEmpty()) {
                append("\nBy calendar:")
                cats.forEach { append('\n').append(line(it)) }
            }
        }
    }
}
