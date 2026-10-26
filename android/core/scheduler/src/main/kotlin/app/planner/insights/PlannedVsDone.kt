package app.planner.insights

import java.time.LocalDate
import kotlin.math.roundToInt

/** One planned block: how long, on which day, and whether its task got finished. */
data class WorkItem(val date: LocalDate, val minutes: Int, val done: Boolean)

data class DoneRow(val date: LocalDate, val weekday: String, val plannedMin: Int, val doneMin: Int) {
    /** Share finished, or null when nothing was planned that day. */
    val percent: Int? get() = if (plannedMin == 0) null else (doneMin * 100.0 / plannedMin).roundToInt()
}

object PlannedVsDone {
    fun rows(items: List<WorkItem>, dates: List<LocalDate>): List<DoneRow> =
        dates.map { date ->
            val day = items.filter { it.date == date }
            DoneRow(date, SleepInsights.weekday(date.dayOfWeek), day.sumOf { it.minutes }, day.filter { it.done }.sumOf { it.minutes })
        }

    fun totalPlannedMin(rows: List<DoneRow>): Int = rows.sumOf { it.plannedMin }

    /** Done over planned across all the days, weighted by minutes; null if nothing was planned. */
    fun overallPercent(rows: List<DoneRow>): Int? {
        val planned = totalPlannedMin(rows)
        return if (planned == 0) null else (rows.sumOf { it.doneMin } * 100.0 / planned).roundToInt()
    }

    /** Each day's planned time as a fraction of the busiest day, for bar heights. */
    fun heightFractions(rows: List<DoneRow>): List<Double> {
        val max = rows.maxOfOrNull { it.plannedMin }?.takeIf { it > 0 } ?: return rows.map { 0.0 }
        return rows.map { it.plannedMin.toDouble() / max }
    }
}
