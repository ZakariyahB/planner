package app.planner.calendar

import android.content.Intent
import android.content.IntentSender
import androidx.room.withTransaction
import app.planner.data.AppDatabase
import app.planner.data.CalendarEventDao
import app.planner.data.SettingsRepository
import app.planner.data.toEntity
import app.planner.data.toEvent
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface SyncResult {
    data object Synced : SyncResult
    data class NeedsConsent(val sender: IntentSender) : SyncResult
    data class Failed(val message: String) : SyncResult
}

@Singleton
class CalendarRepository @Inject constructor(
    private val auth: CalendarAuth,
    private val client: GoogleCalendarClient,
    private val db: AppDatabase,
    private val events: CalendarEventDao,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    val cachedEvents: Flow<List<CalendarEvent>> = events.observeAll().map { rows -> rows.map { it.toEvent() } }

    /** Refreshes the calendar list and the events of the selected and prayer calendars from yesterday to [WINDOW_DAYS] ahead. */
    suspend fun sync(): SyncResult = syncWith(auth.authorize())

    /** Called with the intent returned by the consent screen. */
    suspend fun finishConsent(data: Intent?): SyncResult = syncWith(auth.completeConsent(data))

    private suspend fun syncWith(outcome: AuthOutcome): SyncResult {
        val token = when (outcome) {
            is AuthOutcome.Token -> outcome.accessToken
            is AuthOutcome.NeedsConsent -> return SyncResult.NeedsConsent(outcome.sender)
            is AuthOutcome.Failed -> return SyncResult.Failed(outcome.message)
        }
        return try {
            val calendars = client.listCalendars(token)
            settings.saveCalendars(calendars)
            val selected = settings.selection() ?: FixedEvents.defaultSelection(calendars).also { settings.saveSelection(it) }

            val today = LocalDate.now(clock).atStartOfDay(clock.zone).toInstant()
            val from = today.minus(Duration.ofDays(1))
            val to = today.plus(Duration.ofDays(WINDOW_DAYS))
            // The prayer calendar is always read (for prayer times) even though it is not a fixed-commitment
            // source; the Planner calendar is ours and is synced separately.
            val plannerId = settings.plannerCalendar()
            val toFetch = calendars.filter { (it.id in selected || PrayerTimes.isPrayerCalendar(it.name)) && it.id != plannerId }
            val fetched = toFetch.flatMap { client.listEvents(token, it.id, from, to) }

            db.withTransaction {
                events.deleteAll()
                events.insertAll(fetched.map { it.toEntity() })
            }
            settings.saveLastSynced(clock.instant())
            SyncResult.Synced
        } catch (e: CalendarHttpException) {
            SyncResult.Failed(if (e.code == 401 || e.code == 403) "Google rejected the access (${e.code}). Reconnect Calendar." else e.message.orEmpty())
        } catch (e: IOException) {
            SyncResult.Failed("No connection to Google Calendar")
        }
    }

    suspend fun setSelection(ids: Set<String>): SyncResult {
        settings.saveSelection(ids)
        return sync()
    }

    private companion object {
        const val WINDOW_DAYS = 21L
    }
}
