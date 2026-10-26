package app.planner.routines

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Lets a [CoroutineWorker] reach the app's singletons without hilt-work. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RoutineEntryPoint {
    fun runner(): RoutineRunner
}

/** Entry points for starting routine work from receivers and from app start. */
object RoutineWork {
    const val KEY_JOB = "job"
    const val JOB_RESCHEDULE = "reschedule"
    const val JOB_MORNING_UNLOCK = "morning_unlock"
    const val JOB_LIGHTEN = "lighten"

    private const val PERIODIC_NAME = "routines-periodic"
    private const val PERIODIC_MINUTES = 15L

    fun runner(context: Context): RoutineRunner =
        EntryPointAccessors.fromApplication(context.applicationContext, RoutineEntryPoint::class.java).runner()

    /** A timed routine's alarm went off. */
    fun fire(context: Context, routine: Routine) = enqueue(context, routine.name, "routine-${routine.name}", ExistingWorkPolicy.REPLACE)

    /** Set every alarm again, e.g. after boot, a time zone change or an app update. */
    fun reschedule(context: Context) = enqueue(context, JOB_RESCHEDULE, "routines-reschedule", ExistingWorkPolicy.REPLACE)

    /** The phone was unlocked: this may be the first time he is up. */
    fun morningOnUnlock(context: Context) = enqueue(context, JOB_MORNING_UNLOCK, "routines-unlock", ExistingWorkPolicy.KEEP)

    /** The "Lighten today" button on the morning brief. */
    fun lighten(context: Context) = enqueue(context, JOB_LIGHTEN, "routines-lighten", ExistingWorkPolicy.KEEP)

    /** The 15-minute job: sleep sync, early-wake notice, 10:00 morning fallback, Monday catch-up, alarm upkeep. */
    fun startPeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun enqueue(context: Context, job: String, uniqueName: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<RoutineWorker>().setInputData(workDataOf(KEY_JOB to job)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, policy, request)
    }
}

private const val TAG = "RoutineWork"

/** Runs one routine job. A failure is logged, never retried: a notification that is late is a wrong one. */
class RoutineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            RoutineWork.runner(applicationContext).runJob(inputData.getString(RoutineWork.KEY_JOB))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Routine job failed", e)
        }
        return Result.success()
    }
}

/** The 15-minute job. */
class PeriodicSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            RoutineWork.runner(applicationContext).periodic()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Periodic routine job failed", e)
        }
        return Result.success()
    }
}
