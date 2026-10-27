package app.planner.routines

import app.planner.readiness.BackToBedAdvice
import app.planner.readiness.Readiness
import app.planner.readiness.ReadinessBand
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class NotificationText(val title: String, val body: String)

/**
 * What a day looks like in a notification: how many tasks and when the first one starts, plus the calendar
 * events at fixed times and the tasks that still have no time, so a day is never called empty while either exists.
 */
data class DayOutline(
    val taskCount: Int,
    val firstStart: LocalTime?,
    val firstTitle: String?,
    val fixedCount: Int = 0,
    val unplacedCount: Int = 0,
)

/** A task that was not finished today and where it is planned now; a null [to] means it fits nowhere this week. */
data class MovedTask(val title: String, val to: LocalDate?)

data class WorkStopsSummary(val done: Int, val planned: Int, val moved: List<MovedTask>, val today: LocalDate)

/** Tomorrow's score for lights-out on time, and for his usual lights-out when there is enough history. */
data class ForecastNote(
    val onTimeLightsOut: LocalTime,
    val onTimeScore: Int,
    val usualLightsOut: LocalTime?,
    val usualScore: Int?,
)

/** Plain, short and honest notification texts. Nothing here needs the AI, so every routine can always post. */
object NotificationTexts {
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    fun provisionalReadiness(asleepSoFarMin: Int, readiness: Readiness, advice: BackToBedAdvice?): NotificationText {
        val so = "${duration(asleepSoFarMin)} so far"
        return when {
            advice == null -> NotificationText(
                "Readiness so far: ${readiness.score}",
                "$so and your alarm time has passed. The final score comes once you're up.",
            )
            advice.sleepMin <= 0 -> NotificationText(
                "Enough sleep",
                "$so, that's enough. Keep the alarm at ${clock.format(advice.alarm)}. Readiness so far: ${readiness.score}.",
            )
            else -> NotificationText(
                "Back to bed",
                "$so, back to bed, alarm ${clock.format(advice.alarm)}. Aim for up to ${duration(advice.sleepMin)} more. Readiness so far: ${readiness.score}.",
            )
        }
    }

    /** [loadCutCount] is how many tasks moving off today would give the lighter day; 0 when nothing was proposed. */
    fun morningBrief(readiness: Readiness, day: DayOutline, loadCutCount: Int): NotificationText {
        val title = when (readiness.band) {
            ReadinessBand.GREEN -> "Readiness ${readiness.score}: full plan today"
            ReadinessBand.AMBER -> "Readiness ${readiness.score}: lighter day suggested"
            ReadinessBand.RED -> "Readiness ${readiness.score}: low-energy day"
        }
        val parts = mutableListOf("Slept ${duration(readiness.inputs.asleepMin)}.", plannedSentence(day, "today"))
        when (readiness.band) {
            ReadinessBand.GREEN -> Unit
            ReadinessBand.AMBER -> parts += if (loadCutCount > 0) {
                "Moving ${tasks(loadCutCount)} off today would make it about 20% lighter; open the app to review."
            } else {
                "Aim for about 20% less today."
            }
            ReadinessBand.RED -> parts += if (loadCutCount > 0) {
                "Moving ${tasks(loadCutCount)} off today keeps it to what is due within 48 hours; open the app to review."
            } else {
                "Keep to what is due within 48 hours."
            }
        }
        return NotificationText(title, parts.joinToString(" "))
    }

    fun workStops(s: WorkStopsSummary): NotificationText {
        val body = when {
            s.planned == 0 -> "Nothing was planned today."
            s.done >= s.planned -> if (s.planned == 1) "The task is done." else "All ${s.planned} tasks done."
            else -> {
                val head = "${s.done} of ${tasks(s.planned)} done."
                if (s.moved.isEmpty()) "$head Nothing moved." else "$head Moved: ${s.moved.joinToString(", ") { moved(it, s.today) }}."
            }
        }
        return NotificationText("Work stops", body)
    }

    fun planReady(day: DayOutline, forecast: ForecastNote?): NotificationText {
        val parts = mutableListOf(plannedSentence(day, "tomorrow", withPlanned = false))
        if (forecast != null) parts += forecastLine(forecast)
        return NotificationText("Tomorrow's plan is ready", parts.joinToString(" "))
    }

    /** "Lights-out at 00:15 gives readiness 78; your usual 01:27 gives 52." */
    fun forecastLine(f: ForecastNote): String {
        val usual = if (f.usualLightsOut != null && f.usualScore != null) "; your usual ${clock.format(f.usualLightsOut)} gives ${f.usualScore}" else ""
        return "Lights-out at ${clock.format(f.onTimeLightsOut)} gives readiness ${f.onTimeScore}$usual."
    }

    fun lightsOutSoon(lightsOut: LocalTime, wake: LocalTime, minutes: Int = RoutineSchedule.LIGHTS_OUT_WARNING_MIN): NotificationText {
        val inBed = Duration.between(lightsOut, wake).let { if (it.isNegative) it.plusDays(1) else it }.toMinutes().toInt()
        return NotificationText(
            "Lights-out in $minutes minutes",
            "Lights-out is ${clock.format(lightsOut)}, which gives ${duration(inBed)} in bed before ${clock.format(wake)}. Start winding down.",
        )
    }

    fun sundayReview(plannedMin: Int, doneMin: Int): NotificationText {
        val tail = "Take a few minutes to plan next week."
        if (plannedMin <= 0) return NotificationText("Sunday review", tail)
        val percent = Math.round(doneMin * 100.0 / plannedMin).toInt()
        return NotificationText(
            "Sunday review",
            "You did ${duration(doneMin)} of the ${duration(plannedMin)} planned this week ($percent%). $tail",
        )
    }

    private fun tasks(n: Int) = if (n == 1) "1 task" else "$n tasks"

    private fun plannedSentence(day: DayOutline, whenText: String, withPlanned: Boolean = true): String {
        val pieces = mutableListOf<String>()
        if (day.taskCount > 0) {
            val first = when {
                day.firstTitle != null && day.firstStart != null -> ", first ${day.firstTitle} at ${clock.format(day.firstStart)}"
                day.firstStart != null -> ", first at ${clock.format(day.firstStart)}"
                else -> ""
            }
            pieces += "${tasks(day.taskCount)}${if (withPlanned) " planned" else ""}$first"
        }
        if (day.fixedCount > 0) pieces += if (day.fixedCount == 1) "1 calendar event" else "${day.fixedCount} calendar events"
        if (day.unplacedCount > 0) pieces += "${tasks(day.unplacedCount)} without a time yet"
        return if (pieces.isEmpty()) "Nothing planned $whenText." else pieces.joinToString("; ") + "."
    }

    private fun moved(m: MovedTask, today: LocalDate): String = when (m.to) {
        null -> "${m.title} doesn't fit this week"
        today.plusDays(1) -> "${m.title} to tomorrow"
        else -> "${m.title} to ${m.to.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK)}"
    }
}
