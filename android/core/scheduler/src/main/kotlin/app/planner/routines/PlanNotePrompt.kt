package app.planner.routines

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The request behind the optional AI note on the 23:30 notification. Everything it needs is in the text. */
object PlanNotePrompt {
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    fun build(date: LocalDate, rows: List<PlanRow>, forecast: ForecastNote?, facts: DayFacts = DayFacts()): String = buildString {
        appendLine("This is the 23:30 scheduled run. It is read-only: do not propose or apply any change, do not add anything, and you do not need any tool, everything is below.")
        appendLine("Write a friendly note for the notification on tomorrow's plan: two short sentences, at most 45 words, plain text, no headings, no emojis.")
        appendLine("Say what the day looks like (heavy or light, where the pressure is) and, if the forecast shows lights-out time matters, say so honestly and kindly.")
        appendLine("Count everything below: tasks with a time, calendar events at fixed times, all-day calendar items (the planner gives each a time), and tasks still waiting for a time. Only call the day free when every list is empty.")
        appendLine()
        appendLine("Tomorrow, ${day.format(date)}:")
        if (rows.isEmpty() && facts.fixed.isEmpty() && facts.unplaced.isEmpty()) {
            appendLine("- nothing planned")
        } else {
            if (rows.isNotEmpty()) {
                appendLine("Tasks with a time:")
                rows.forEach { r ->
                    val tags = listOfNotNull(
                        "due tomorrow".takeIf { r.dueByTomorrow },
                        "all-day calendar item, given a time".takeIf { r.fromCalendar },
                    )
                    appendLine("- ${at(r.start)}-${at(r.end)} ${r.title}${if (tags.isEmpty()) "" else " (${tags.joinToString(", ")})"}")
                }
            }
            if (facts.fixed.isNotEmpty()) {
                appendLine("Calendar events at fixed times:")
                facts.fixed.forEach { f -> appendLine("- ${at(f.start)}-${at(f.end)} ${f.title}") }
            }
            if (facts.unplaced.isNotEmpty()) {
                appendLine("Tasks still without a time (the planner found no room yet):")
                facts.unplaced.forEach { appendLine("- $it") }
            }
        }
        appendLine()
        if (forecast == null) {
            append("No forecast available.")
        } else {
            append("Forecast: lights-out at ${at(forecast.onTimeLightsOut)} gives readiness ${forecast.onTimeScore}")
            if (forecast.usualLightsOut != null && forecast.usualScore != null) {
                append("; his usual lights-out ${at(forecast.usualLightsOut)} gives ${forecast.usualScore}")
            }
            append(".")
        }
    }

    private fun at(t: LocalTime) = clock.format(t)
}
