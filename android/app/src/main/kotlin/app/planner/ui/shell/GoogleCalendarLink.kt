package app.planner.ui.shell

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDateTime
import java.time.ZoneId

/** Opens Google Calendar at [at] (the app shows a list; Google Calendar is where the plan is drawn). */
fun openGoogleCalendar(context: Context, at: LocalDateTime, zone: ZoneId) {
    val millis = at.atZone(zone).toInstant().toEpochMilli()
    val time = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/$millis"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val candidates = listOf(
        Intent(time).setPackage("com.google.android.calendar"),
        time,
        Intent(Intent.ACTION_VIEW, Uri.parse("https://calendar.google.com/calendar/r/day/${at.year}/${at.monthValue}/${at.dayOfMonth}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next way of opening it.
        } catch (_: SecurityException) {
        }
    }
}
