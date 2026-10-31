package app.planner.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The AlarmManager side of check-ins and prompts. Each alarm is keyed by its stable id through the intent's data
 * URI, so setting the same id again replaces the alarm (PendingIntent identity ignores extras but not data).
 */
@Singleton
class CheckInAlarms @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    /** Exact alarms need the user's say-so on Android 12 and 13 (SCHEDULE_EXACT_ALARM); 14+ grants USE_EXACT_ALARM. */
    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return manager?.canScheduleExactAlarms() == true
    }

    /**
     * Sets (or replaces) the alarm [id] at [atMillis] carrying [payload]. Returns true if it is exact; without the
     * permission it falls back to an inexact alarm that may drift by some minutes.
     */
    fun set(id: String, atMillis: Long, payload: String): Boolean {
        val manager = manager ?: return false
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent(id).putExtra(EXTRA_PAYLOAD, payload),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (canScheduleExact()) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
                return true
            } catch (e: SecurityException) {
                // Permission withdrawn between the check and the call: fall through to the inexact alarm.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        return false
    }

    fun cancel(id: String) {
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent(id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        manager?.cancel(pending)
        pending.cancel()
    }

    private fun intent(id: String): Intent =
        Intent(context, CheckInAlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .setData(Uri.Builder().scheme(SCHEME).opaquePart(id).build())

    companion object {
        const val ACTION_ALARM = "app.planner.checkin.ALARM"
        const val EXTRA_PAYLOAD = "app.planner.checkin.PAYLOAD"
        private const val SCHEME = "planner-checkin"
        private const val REQUEST_CODE = 0
    }
}
