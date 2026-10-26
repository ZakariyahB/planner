package app.planner.social

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.planner.MainActivity
import app.planner.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the social-time notifications on their own channels, so either can be muted in system settings.
 * Asking for the notification permission is not done here; without it nothing is posted.
 */
@Singleton
class SocialNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /** False when notifications are blocked for the app (including Android 13+ before the permission is given). */
    fun canNotify(): Boolean = manager.areNotificationsEnabled()

    fun nudge(text: String) = post(CHANNEL_NUDGES, ID_NUDGE, "Social time", text)

    fun summary(text: String) = post(CHANNEL_SUMMARY, ID_SUMMARY, "Social time today", text)

    // canNotify() is the permission check; callers don't post without it.
    @SuppressLint("MissingPermission")
    private fun post(channel: String, id: Int, title: String, text: String) {
        if (!canNotify()) return
        createChannels()
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_social_nudge)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(id, notification)
    }

    // Creating a channel that already exists changes nothing, so this is safe before every post.
    private fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_NUDGES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Social time nudges")
                .setDescription("A gentle heads-up at 50%, 80% and 100% of your daily social budget")
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_SUMMARY, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Social time summary")
                .setDescription("How the day went against your social budget, once in the evening")
                .build(),
        )
    }

    companion object {
        const val CHANNEL_NUDGES = "social_nudges"
        const val CHANNEL_SUMMARY = "social_summary"
        private const val ID_NUDGE = 6001
        private const val ID_SUMMARY = 6002
    }
}
