package app.planner.calendar

import app.planner.data.BlockDao
import app.planner.data.BlockEntity
import app.planner.data.SettingsRepository
import app.planner.data.TaskRepository
import app.planner.errors.PlainErrors
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PlannerSyncResult {
    data object Done : PlannerSyncResult
    data object NotConnected : PlannerSyncResult
    data class Failed(val message: String) : PlannerSyncResult
}

/**
 * Mirrors planned task blocks to the app's own "Planner" Google calendar and reads edits made there
 * back into the app. All of the decisions are made by the pure [PlannerSync.diff]; this class only
 * executes them. Never touches any other calendar.
 */
@Singleton
class PlannerCalendarSync @Inject constructor(
    private val auth: CalendarAuth,
    private val client: GoogleCalendarClient,
    private val settings: SettingsRepository,
    private val blocks: BlockDao,
    private val tasks: TaskRepository,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun sync(): PlannerSyncResult = mutex.withLock {
        if (settings.lastSyncedAt.first() == null) return PlannerSyncResult.NotConnected
        val token = when (val outcome = auth.authorize()) {
            is AuthOutcome.Token -> outcome.accessToken
            is AuthOutcome.NeedsConsent -> return PlannerSyncResult.Failed("${PlainErrors.PLANNER_NOT_COPIED} Reconnect Google Calendar from Settings, then try again.")
            is AuthOutcome.Failed -> return PlannerSyncResult.Failed(outcome.message)
        }
        try {
            syncWith(token)
            PlannerSyncResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: CalendarHttpException) {
            PlannerSyncResult.Failed(PlainErrors.plannerCalendar(e.code))
        } catch (e: IOException) {
            PlannerSyncResult.Failed("${PlainErrors.PLANNER_NOT_COPIED} ${PlainErrors.CALENDAR_OFFLINE}")
        } catch (e: Exception) {
            PlannerSyncResult.Failed("${PlainErrors.PLANNER_NOT_COPIED} ${PlainErrors.CALENDAR_UNKNOWN}") // the plan itself must never fail because of Google
        }
    }

    private suspend fun syncWith(token: String) {
        val zone = clock.zone
        val calendarId = ensureCalendar(token)
        val today = LocalDate.now(clock)
        val from = today.minusDays(1).atStartOfDay(zone).toInstant()
        val to = today.plusDays(WINDOW_DAYS).atStartOfDay(zone).toInstant()

        val remote = client.listEvents(token, calendarId, from, to).mapNotNull { e ->
            (e.timing as? EventTiming.Timed)?.let { RemoteEvent(e.id, e.plannerBlockId, it.start, it.end) }
        }
        // Task blocks and commitments both appear on the Planner calendar.
        val rows = blocks.between(today.minusDays(1).toString(), today.plusDays(WINDOW_DAYS).toString())
        val titles = tasks.snapshot().associate { it.id to it.title }
        val byId = rows.associateBy { it.id }

        val syncBlocks = rows.map { b ->
            SyncBlock(
                id = b.id,
                gcalEventId = b.gcalEventId,
                start = instantOf(b.date, b.startMin),
                end = instantOf(b.date, b.endMin),
                syncedStart = b.syncedStartMillis?.let(Instant::ofEpochMilli),
                syncedEnd = b.syncedEndMillis?.let(Instant::ofEpochMilli),
            )
        }

        for (action in PlannerSync.diff(syncBlocks, remote)) {
            when (action) {
                is SyncAction.CreateRemote -> {
                    val b = byId.getValue(action.blockId)
                    val start = instantOf(b.date, b.startMin)
                    val end = instantOf(b.date, b.endMin)
                    val eventId = client.insertEvent(token, calendarId, titleOf(b, titles), start, end, b.id)
                    blocks.upsert(b.copy(gcalEventId = eventId, syncedStartMillis = start.toEpochMilli(), syncedEndMillis = end.toEpochMilli()))
                }
                is SyncAction.UpdateRemote -> {
                    val b = byId.getValue(action.blockId)
                    val start = instantOf(b.date, b.startMin)
                    val end = instantOf(b.date, b.endMin)
                    client.updateEvent(token, calendarId, action.eventId, titleOf(b, titles), start, end, b.id)
                    blocks.upsert(b.copy(syncedStartMillis = start.toEpochMilli(), syncedEndMillis = end.toEpochMilli()))
                }
                is SyncAction.PullTime -> pull(byId.getValue(action.blockId), action.start, action.end)
                is SyncAction.MarkSynced -> {
                    val b = byId.getValue(action.blockId)
                    blocks.upsert(b.copy(syncedStartMillis = action.start.toEpochMilli(), syncedEndMillis = action.end.toEpochMilli()))
                }
                is SyncAction.DeleteRemote -> client.deleteEvent(token, calendarId, action.eventId)
                // Deleted in Google Calendar: unschedule; the next replan may place the task again.
                is SyncAction.ForgetBlock -> blocks.delete(listOf(action.blockId))
            }
        }
    }

    /** A time edited in Google Calendar wins over the plan: the block follows and becomes locked and user-owned. */
    private suspend fun pull(block: BlockEntity, start: Instant, end: Instant) {
        val s = start.atZone(clock.zone)
        val e = end.atZone(clock.zone)
        val startMin = s.hour * 60 + s.minute
        val endMin = if (e.toLocalDate() == s.toLocalDate()) e.hour * 60 + e.minute else 24 * 60 - 1
        if (endMin <= startMin) return // an event spanning midnight can't be a block; leave it
        blocks.upsert(
            block.copy(
                date = s.toLocalDate().toString(),
                startMin = startMin,
                endMin = endMin,
                locked = true,
                origin = "USER",
                syncedStartMillis = start.toEpochMilli(),
                syncedEndMillis = end.toEpochMilli(),
            ),
        )
    }

    private suspend fun ensureCalendar(token: String): String {
        settings.plannerCalendar()?.let { return it }
        val existing = client.listCalendars(token).firstOrNull { it.name == CALENDAR_NAME }?.id
        val id = existing ?: client.createCalendar(token, CALENDAR_NAME, clock.zone.id)
        settings.savePlannerCalendar(id)
        return id
    }

    private fun titleOf(block: BlockEntity, titles: Map<Long, String>): String =
        block.taskId?.let(titles::get) ?: block.label ?: "Planned block"

    private fun instantOf(date: String, minuteOfDay: Int): Instant =
        LocalDate.parse(date).atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(clock.zone).toInstant()

    private companion object {
        const val CALENDAR_NAME = "Planner"
        const val WINDOW_DAYS = 21L
    }
}
