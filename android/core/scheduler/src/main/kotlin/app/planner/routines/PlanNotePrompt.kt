package app.planner.routines

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The request behind the optional AI note on the 23:30 notification. Everything it needs is in the text. */
object PlanNotePrompt {
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    fun build(date: LocalDate, rows: List<PlanRow>, forecast: ForecastNote?): String = buildString {
        appendLine("This is the 23:30 scheduled run. It is read-only: do not propose or apply any change, do not add anything, and you do not need any tool, everything is below.")
        appendLine("Write a friendly note for the notification on tomorrow's plan: two short sentences, at most 45 words, plain text, no headings, no emojis.")
        appendLine("Say what the day looks like (heavy or light, where the pressure is) and, if the forecast shows lights-out time matters, say so honestly and kindly.")
        appendLine()
        appendLine("Tomorrow, ${day.format(date)}:")
        if (rows.isEmpty()) {
            appendLine("- nothing planned")
        } else {
            rows.forEach { r ->
                appendLine("- ${at(r.start)}-${at(r.end)} ${r.title}${if (r.dueByTomorrow) " (due tomorrow)" else ""}")
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
