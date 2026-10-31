package app.planner.checkin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** What check-ins need from the system, and where to send the user to grant it. */
data class CheckInReadiness(
    /** POST_NOTIFICATIONS on Android 13+, and notifications not blocked for the app. */
    val notifications: Boolean,
    /** Exact alarms allowed; without them alarms are inexact and may be minutes late. */
    val exactAlarms: Boolean,
    /** The app is exempt from battery optimisation, so a deep-idle phone still rings on time. */
    val batteryUnrestricted: Boolean,
) {
    val allSet: Boolean get() = notifications && exactAlarms && batteryUnrestricted
}

object CheckInPermissions {
    fun readiness(context: Context): CheckInReadiness = CheckInReadiness(
        notifications = notificationsAllowed(context),
        exactAlarms = CheckInAlarms(context).canScheduleExact(),
        batteryUnrestricted = batteryUnrestricted(context),
    )

    /** True if the runtime permission must still be asked for (Android 13+). */
    fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun notificationsAllowed(context: Context): Boolean =
        !needsNotificationPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** The app's notification settings (for when notifications were blocked after the permission was given). */
    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android 12+: the "Alarms & reminders" switch for this app. Null where there is no such screen. */
    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The battery optimisation list, where the user picks the app and chooses "Don't optimise" / "Unrestricted".
     * The direct one-tap request needs a permission that Play reserves for a few app types, so it is not used.
     */
    fun batterySettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts [intent] if some app can handle it; falls back to the app's own details page. */
    fun open(context: Context, intent: Intent?): Boolean {
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (candidate in listOfNotNull(intent, fallback)) {
            try {
                context.startActivity(candidate)
                return true
            } catch (e: android.content.ActivityNotFoundException) {
                // try the next one
            }
        }
        return false
    }
}
