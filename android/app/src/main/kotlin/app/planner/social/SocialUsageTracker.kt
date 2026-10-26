package app.planner.social

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import app.planner.data.SocialUsageDao
import app.planner.data.SocialUsageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Minutes on the selected social apps for one day, in total and per package. */
data class TrackedDay(val date: LocalDate, val minutes: Int, val perApp: Map<String, Int>)

/** Reads Android's usage events and keeps the day's row in `social_usage` up to date. */
@Singleton
class SocialUsageTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SocialUsageDao,
    private val settings: SocialSettings,
    private val access: UsageAccess,
    private val clock: Clock,
) {
    private val json = Json

    /** Today's usage after saving it, or null without usage access (nothing is saved then). */
    suspend fun refresh(): TrackedDay? = withContext(Dispatchers.Default) {
        if (!access.isGranted()) return@withContext null
        try {
            val today = LocalDate.now(clock)
            val tracked = track(today)
            // The minutes after the last check before midnight are only seen by the first checks of the new day.
            if (LocalTime.now(clock) < FINISH_YESTERDAY_BEFORE) track(today.minusDays(1))
            tracked
        } catch (e: SecurityException) {
            null // access was taken away while reading
        }
    }

    private suspend fun track(date: LocalDate): TrackedDay {
        val from = date.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val now = clock.millis()
        val apps = settings.packages()
        val perApp = if (apps.isEmpty()) emptyMap() else UsageSessions.minutesPerApp(events(apps, from, minOf(to, now)), apps, from, to, now)
        val total = UsageSessions.total(perApp)
        dao.put(SocialUsageEntity(date.toString(), total, json.encodeToString(perApp)))
        return TrackedDay(date, total, perApp)
    }

    private fun events(apps: Set<String>, from: Long, to: Long): List<UsageEvent> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val raw = manager.queryEvents(from, to) ?: return emptyList()
        val found = mutableListOf<UsageEvent>()
        val event = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(event)
            val pkg = event.packageName
            if (pkg !in apps) continue
            val type = when (event.eventType) {
                FOREGROUND -> UsageEventType.FOREGROUND
                BACKGROUND -> UsageEventType.BACKGROUND
                else -> continue
            }
            found += UsageEvent(pkg, type, event.timeStamp)
        }
        return found
    }

    private companion object {
        val FINISH_YESTERDAY_BEFORE: LocalTime = LocalTime.of(4, 0)

        // Activity resumed/paused on Android 10+; the older foreground/background events before that.
        val FOREGROUND = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) UsageEvents.Event.ACTIVITY_RESUMED else legacyForeground()
        val BACKGROUND = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) UsageEvents.Event.ACTIVITY_PAUSED else legacyBackground()

        @Suppress("DEPRECATION")
        fun legacyForeground() = UsageEvents.Event.MOVE_TO_FOREGROUND

        @Suppress("DEPRECATION")
        fun legacyBackground() = UsageEvents.Event.MOVE_TO_BACKGROUND
    }
}
