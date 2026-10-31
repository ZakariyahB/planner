package app.planner.checkin

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.RemoteInput
import app.planner.engine.checkin.AlarmPayload
import app.planner.engine.checkin.CheckInAction
import app.planner.engine.checkin.CheckInBlock
import app.planner.engine.nudge.Nudge
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Lets receivers and workers reach the app's singletons without hilt-work. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CheckInEntryPoint {
    fun actions(): CheckInActions
    fun scheduler(): CheckInScheduler
}

internal fun Context.checkIns(): CheckInEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, CheckInEntryPoint::class.java)

private const val TAG = "CheckInReceivers"
private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. Failures are logged. */
private fun BroadcastReceiver.runAsync(what: String, block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "$what failed", e)
        } finally {
            pending.finish()
        }
    }
}

/** A check-in or prompt alarm went off: post its notification (if it is still due). */
class CheckInAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CheckInAlarms.ACTION_ALARM) return
        val payload = AlarmPayload.decode(intent.getStringExtra(CheckInAlarms.EXTRA_PAYLOAD)) ?: return
        val app = context.applicationContext
        runAsync("check-in alarm") { app.checkIns().actions().onAlarm(payload) }
    }
}

/** Buttons on check-ins and prompts. Logging happens here; a replan is handed to a worker. */
class CheckInActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_CHECK_IN -> {
                val action = CheckInAction.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_ACTION) } ?: return
                val checkIn = (AlarmPayload.decode(intent.getStringExtra(CheckInAlarms.EXTRA_PAYLOAD)) as? AlarmPayload.CheckInAlarm)?.checkIn ?: return
                runAsync("check-in action") {
                    app.checkIns().actions().onAction(action, checkIn)?.let { CheckInWork.replan(app, it) }
                }
            }
            ACTION_FOCUS -> {
                val block = AlarmPayload.decodeBlock(intent.getStringExtra(EXTRA_BLOCK)) ?: return
                val rating = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_FOCUS)?.toString()?.trim()?.toIntOrNull()
                runAsync("focus rating") { app.checkIns().actions().onFocus(block, rating) }
            }
            ACTION_FAJR -> {
                val nudge = (AlarmPayload.decode(intent.getStringExtra(CheckInAlarms.EXTRA_PAYLOAD)) as? AlarmPayload.NudgeAlarm)?.nudge ?: return
                val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_FAJR)?.toString()
                runAsync("Fajr reply") { app.checkIns().actions().onFajr(nudge, reply) }
            }
        }
    }

    companion object {
        const val ACTION_CHECK_IN = "app.planner.checkin.ACTION"
        const val ACTION_FOCUS = "app.planner.checkin.FOCUS"
        const val ACTION_FAJR = "app.planner.checkin.FAJR"
        const val EXTRA_ACTION = "app.planner.checkin.ACTION_NAME"
        const val EXTRA_BLOCK = "app.planner.checkin.BLOCK"
        const val KEY_FOCUS = "focus"
        const val KEY_FAJR = "fajr"

        /** One PendingIntent per check-in and action: the data URI keeps them apart. */
        fun pending(context: Context, action: CheckInAction, id: String, payload: String): PendingIntent {
            val intent = Intent(context, CheckInActionReceiver::class.java)
                .setAction(ACTION_CHECK_IN)
                .setData(Uri.Builder().scheme("planner-checkin-action").opaquePart("${action.name}|$id").build())
                .putExtra(EXTRA_ACTION, action.name)
                .putExtra(CheckInAlarms.EXTRA_PAYLOAD, payload)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun focusPending(context: Context, block: CheckInBlock): PendingIntent {
            val intent = Intent(context, CheckInActionReceiver::class.java)
                .setAction(ACTION_FOCUS)
                .setData(Uri.Builder().scheme("planner-checkin-focus").opaquePart(block.key).build())
                .putExtra(EXTRA_BLOCK, AlarmPayload.encodeBlock(block))
            return PendingIntent.getBroadcast(context, 0, intent, CheckInNotifier.MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun fajrPending(context: Context, nudge: Nudge): PendingIntent {
            val intent = Intent(context, CheckInActionReceiver::class.java)
                .setAction(ACTION_FAJR)
                .setData(Uri.Builder().scheme("planner-checkin-fajr").opaquePart(nudge.id).build())
                .putExtra(CheckInAlarms.EXTRA_PAYLOAD, AlarmPayload.encode(AlarmPayload.NudgeAlarm(nudge)))
            return PendingIntent.getBroadcast(context, 0, intent, CheckInNotifier.MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

/**
 * Alarms do not survive a reboot, and their wall-clock times move with the zone or a clock change, so every one is
 * set again. Also runs after an app update and when the exact-alarm permission is granted or withdrawn.
 */
class CheckInSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in SYSTEM_ACTIONS) CheckInWork.reschedule(context.applicationContext, force = true)
    }

    private companion object {
        val SYSTEM_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
