package app.planner.checkin

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import app.planner.R

/** newplan 10: check-ins at default importance, prompts low. Each can be muted from the system settings. */
object CheckInChannels {
    const val CHECK_INS = "checkins"
    const val PROMPTS = "prompts"

    /** Safe to call on every start: creating a channel that exists changes nothing the user has set. */
    fun create(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHECK_INS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.checkin_channel_checkins))
                .setDescription(context.getString(R.string.checkin_channel_checkins_desc))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(PROMPTS, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.checkin_channel_prompts))
                .setDescription(context.getString(R.string.checkin_channel_prompts_desc))
                .build(),
        )
    }
}
