package app.planner.calendar

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

    /** Refreshes the calendar list and the events of the selected calendars from yesterday to [WINDOW_DAYS] ahead. */
    suspend fun sync(): SyncResult {
        val token = when (val outcome = auth.authorize()) {
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
            val fetched = calendars.filter { it.id in selected }.flatMap { client.listEvents(token, it.id, from, to) }

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
