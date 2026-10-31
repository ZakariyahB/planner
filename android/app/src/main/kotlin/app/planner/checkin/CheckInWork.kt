package app.planner.checkin

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.planner.engine.model.EventRef
import app.planner.engine.ports.ReplanReason
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException

/** Background jobs for check-ins: rescheduling the alarms and running a replan asked for from a notification. */
object CheckInWork {
    private const val KEY_FORCE = "force"
    private const val KEY_KIND = "kind"
    private const val KEY_CALENDAR = "calendarId"
    private const val KEY_EVENT = "eventId"
    private const val KEY_EXTRA_MIN = "extraMin"
    private const val KEY_AT = "at"
    private const val KIND_OVERRUN = "overrun"
    private const val KIND_STOPPED = "stopped"

    /** Everything check-ins need at process start: channels and the alarms (forced: a force-stop drops them). */
    fun start(app: Application) {
        CheckInChannels.create(app)
        reschedule(app, force = true)
    }

    fun reschedule(context: Context, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<CheckInRescheduleWorker>().setInputData(workDataOf(KEY_FORCE to force)).build()
        // A forced run must not be dropped in favour of a queued plain one, so the two queue separately.
        val name = if (force) "checkins-reschedule-force" else "checkins-reschedule"
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    fun replan(context: Context, reason: ReplanReason) {
        val data = encode(reason) ?: return
        val request = OneTimeWorkRequestBuilder<CheckInReplanWorker>().setInputData(data).build()
        WorkManager.getInstance(context).enqueue(request)
    }

    internal fun force(data: Data): Boolean = data.getBoolean(KEY_FORCE, false)

    private fun encode(reason: ReplanReason): Data? = when (reason) {
        is ReplanReason.Overrun -> workDataOf(
            KEY_KIND to KIND_OVERRUN, KEY_CALENDAR to reason.ref.calendarId, KEY_EVENT to reason.ref.eventId,
            KEY_EXTRA_MIN to reason.extraMin,
        )
        is ReplanReason.StoppedEarly -> workDataOf(
            KEY_KIND to KIND_STOPPED, KEY_CALENDAR to reason.ref.calendarId, KEY_EVENT to reason.ref.eventId,
            KEY_AT to reason.at.toString(),
        )
        else -> null // check-ins only ask for these two
    }

    internal fun decode(data: Data): ReplanReason? {
        val ref = EventRef(data.getString(KEY_CALENDAR) ?: return null, data.getString(KEY_EVENT) ?: return null)
        return when (data.getString(KEY_KIND)) {
            KIND_OVERRUN -> ReplanReason.Overrun(ref, data.getInt(KEY_EXTRA_MIN, 30))
            KIND_STOPPED -> ReplanReason.StoppedEarly(ref, runCatching { LocalDateTime.parse(data.getString(KEY_AT)) }.getOrNull() ?: return null)
            else -> null
        }
    }
}

private const val TAG = "CheckInWork"

/** A failure is logged, not retried: the next sync or boot reschedules anyway. */
class CheckInRescheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            applicationContext.checkIns().scheduler().reschedule(force = CheckInWork.force(inputData))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Check-in reschedule failed", e)
        }
        return Result.success()
    }
}

/** "Need 30 more" / "Stop here": asks the engine for a proposal and posts "Review change". Never applies anything. */
class CheckInReplanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val reason = CheckInWork.decode(inputData) ?: return Result.success()
        try {
            applicationContext.checkIns().actions().replan(reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Check-in replan failed", e)
        }
        return Result.success()
    }
}
