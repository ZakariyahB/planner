package app.planner.social

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/** Workers are built by WorkManager, not Hilt, so what this one needs is looked up through an entry point. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SocialEntryPoint {
    fun socialCheck(): SocialCheck
}

class SocialUsageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val check = EntryPointAccessors.fromApplication(applicationContext, SocialEntryPoint::class.java).socialCheck()
        return try {
            check.run()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Social time check failed", e)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "SocialUsageWorker"
    }
}
