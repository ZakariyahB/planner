package app.planner.gcal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.planner.engine.model.Settings
import app.planner.engine.ports.SettingsStore
import app.planner.gcal.sync.CalendarSync
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Workers are built by WorkManager, not Hilt, so the sync is looked up through an entry point. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GcalEntryPoint {
    fun calendarSync(): CalendarSync
}

/** The periodic background refresh (newplan 8). Sync never throws for Google problems; it records a stamp. */
class GcalSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(applicationContext, GcalEntryPoint::class.java).calendarSync()
        return try {
            sync.refresh() // a failed stamp is shown in the app; no retry storm
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Calendar refresh failed", e)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "GcalSyncWorker"
    }
}

/**
 * Refresh on app open and every time the app comes to the foreground, plus a periodic WorkManager refresh.
 * Installed once, when the calendar services are first created.
 */
object GcalRefresh {
    private const val WORK_NAME = "gcal_refresh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var installed = false

    fun install(context: Context, sync: CalendarSync) {
        if (installed) return
        installed = true
        val app = context.applicationContext as Application
        schedulePeriodic(app)
        refreshSoon(sync) // app open
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) refreshSoon(sync) // back to the foreground
            }
            override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun refreshSoon(sync: CalendarSync) {
        scope.launch {
            try {
                sync.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GcalRefresh", "Calendar refresh failed", e)
            }
        }
    }

    /** Safe to call on every app open: an existing schedule is kept, not restarted. */
    private fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<GcalSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** "Now" in the user's zone from Settings (default Europe/London), kept current as settings change. */
class ZoneClock(settings: SettingsStore) {
    @Volatile private var zone: ZoneId = Settings().zone

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                settings.observe().collect { zone = it.zone }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ZoneClock", "Settings unavailable; using ${zone.id}", e)
            }
        }
    }

    fun now(): LocalDateTime = LocalDateTime.now(zone)
}
