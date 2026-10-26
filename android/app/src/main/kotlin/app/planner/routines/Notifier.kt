package app.planner.routines

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.planner.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A button on a notification; [intent] is what it fires. */
data class NotificationAction(val label: String, val intent: PendingIntent)

/** Posts a routine's notification on its own channel. Without the permission it quietly does nothing. */
@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {
    /** True if the notification was handed to the system. */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() covers the Android 13 permission
    fun post(
        routine: Routine,
        text: NotificationText,
        target: LaunchTarget = LaunchTarget.TODAY,
        extraLine: String? = null,
        actions: List<NotificationAction> = emptyList(),
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        val open = PendingIntent.getActivity(
            context,
            routine.ordinal,
            RoutineLaunch.intent(context, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = listOfNotNull(text.body, extraLine).joinToString("\n\n")
        val builder = NotificationCompat.Builder(context, RoutineChannels.id(routine))
            .setSmallIcon(R.drawable.ic_stat_planner)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        actions.forEach { builder.addAction(0, it.label, it.intent) }

        return try {
            manager.notify(notificationId(routine), builder.build())
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun cancel(routine: Routine) {
        NotificationManagerCompat.from(context).cancel(notificationId(routine))
    }

    private fun notificationId(routine: Routine) = BASE_ID + routine.ordinal

    private companion object {
        const val BASE_ID = 5_000
    }
}
