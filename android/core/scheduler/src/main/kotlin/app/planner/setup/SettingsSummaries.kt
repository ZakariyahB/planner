package app.planner.setup

import app.planner.scheduler.DayRules
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SleepSource { CONNECTED, NEEDS_PERMISSION, UNAVAILABLE, UNKNOWN }

/** The one-line status shown under each row of the Settings home, so most things can be checked without opening them. */
object SettingsSummaries {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    fun dayRules(rules: DayRules): String =
        "Wake ${TIME.format(rules.wake)}, work stops ${TIME.format(rules.workStop)}, lights-out ${TIME.format(rules.lightsOut)}"

    fun template(count: Int): String = when (count) {
        0 -> "No recurring tasks yet"
        1 -> "1 recurring task"
        else -> "$count recurring tasks"
    }

    fun calendars(connected: Boolean, selected: Int, total: Int): String = when {
        !connected -> "Not connected"
        total == 0 -> "Connected, no calendars found"
        else -> "$selected of $total count as fixed commitments"
    }

    fun socialBudget(weekdayMin: Int, weekendMin: Int): String = "${hours(weekdayMin)} weekdays, ${hours(weekendMin)} weekends"

    fun assistant(configured: Boolean, spendGbp: Double, capGbp: Double): String {
        if (!configured) return "Not set up yet"
        val cap = if (capGbp % 1.0 == 0.0) capGbp.toInt().toString() else String.format(Locale.UK, "%.2f", capGbp)
        return String.format(Locale.UK, "£%.2f of £%s this month", spendGbp, cap)
    }

    fun sleep(source: SleepSource): String = when (source) {
        SleepSource.CONNECTED -> "Health Connect connected"
        SleepSource.NEEDS_PERMISSION -> "Needs permission to read sleep"
        SleepSource.UNAVAILABLE -> "Health Connect isn't available"
        SleepSource.UNKNOWN -> "Checking Health Connect"
    }

    private fun hours(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes / 60}h ${minutes % 60}m"
}
