package app.planner.social

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Runs [SocialUsageWorker] every 15 minutes (WorkManager's minimum) while usage access is on. */
@Singleton
class SocialScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    /** Safe to call on every app open: an existing schedule is kept, not restarted. */
    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<SocialUsageWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "social_usage"
    }
}
