package app.planner.gcal.sync

import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.model.SyncStamp
import app.planner.engine.ports.CalendarInfo
import app.planner.engine.ports.CheckInRescheduler
import app.planner.engine.ports.NowProvider
import app.planner.engine.ports.SnapshotStore
import app.planner.gcal.AuthTracker
import app.planner.gcal.CalendarException
import app.planner.gcal.CalendarGateway
import app.planner.gcal.SyncMessages
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the user changed in Google Calendar themselves since the last sync, found by comparing with snapshots. */
data class UserEdits(
    /** The app's blocks the user moved or retimed: (before, after). Their edit is final. */
    val moved: List<Pair<EventSnapshot, EventSnapshot>>,
    /** The app's blocks the user deleted. */
    val deleted: List<EventSnapshot>,
) {
    val isEmpty: Boolean get() = moved.isEmpty() && deleted.isEmpty()
}

/**
 * newplan 8: pulls the calendar list and events over a rolling window (today -7 to +35 days) into the [SnapshotStore].
 *
 * Incremental sync uses Google's syncToken. Because the window rolls forward every day and a token only reports
 * changes, not events that drift into the window, the first sync of each day is a full re-fetch; later syncs that day
 * are incremental. HTTP 410 (token gone) falls back to a full re-fetch. Stored tokens carry their day as `date|token`.
 *
 * Comparing each changed event with its stored snapshot finds the user's own edits: the writer stores the snapshot of
 * every event it writes, so a changed etag on one of the app's blocks means somebody else (the user) changed it.
 * Never throws for Google problems: expiry marks [AuthTracker] expired and returns a failed stamp.
 */
class CalendarSync(
    private val gateway: CalendarGateway,
    private val snapshots: SnapshotStore,
    private val auth: AuthTracker,
    private val rescheduler: CheckInRescheduler,
    private val now: NowProvider,
    private val clock: () -> Instant,
    private val daysBack: Long = 7,
    private val daysAhead: Long = 35,
) {
    private val mutex = Mutex()
    private val calendarsState = MutableStateFlow<List<CalendarInfo>>(emptyList())

    /** The calendar list from the last successful sync. */
    val calendars: StateFlow<List<CalendarInfo>> = calendarsState.asStateFlow()

    /** Set by the planner service: reacts to the user's own edits (task links, replan proposals). */
    var onUserEdits: suspend (UserEdits) -> Unit = {}

    fun window(): Pair<LocalDate, LocalDate> {
        val today = now.now().toLocalDate()
        return today.minusDays(daysBack) to today.plusDays(daysAhead + 1)
    }

    /** The calendar list, from the last sync or fetched now. Empty when Google cannot be reached. */
    suspend fun calendarList(): List<CalendarInfo> {
        calendarsState.value.takeIf { it.isNotEmpty() }?.let { return it }
        return try {
            gateway.calendars().also { calendarsState.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CalendarException.AuthExpired) {
            auth.expired(); emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun refresh(): SyncStamp = mutex.withLock {
        val stamp = try {
            val changed = pull()
            auth.signedIn()
            if (changed.any) runCatching { rescheduler.reschedule() }
            if (!changed.edits.isEmpty) onUserEdits(changed.edits)
            SyncStamp(clock(), ok = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CalendarException.AuthExpired) {
            auth.expired()
            SyncStamp(clock(), ok = false, message = SyncMessages.SIGN_IN_AGAIN)
        } catch (e: CalendarException.Network) {
            SyncStamp(clock(), ok = false, message = SyncMessages.OFFLINE)
        } catch (e: Exception) {
            SyncStamp(clock(), ok = false, message = SyncMessages.UNKNOWN)
        }
        snapshots.setSync(stamp)
        stamp
    }

    private class Pulled(val any: Boolean, val edits: UserEdits)

    private suspend fun pull(): Pulled {
        val list = gateway.calendars()
        calendarsState.value = list
        val (from, to) = window()
        val today = now.now().toLocalDate().toString()
        val stored = snapshots.events(from, to)
        val listed = list.map { it.id }.toSet()
        var any = false
        val moved = mutableListOf<Pair<EventSnapshot, EventSnapshot>>()
        val deleted = mutableListOf<EventSnapshot>()

        // Calendars that left the list (unsubscribed, deleted) take their events with them.
        stored.filter { it.ref.calendarId !in listed }.map { it.ref }.takeIf { it.isNotEmpty() }?.let {
            snapshots.remove(it); any = true
        }

        for (cal in list) {
            val previous = stored.filter { it.ref.calendarId == cal.id }.associateBy { it.ref }
            val token = snapshots.syncToken(cal.id)?.let { decode(it, today) }
            val (page, full) = try {
                gateway.changesSince(cal.id, token) to (token == null)
            } catch (e: CalendarException.SyncTokenGone) {
                gateway.changesSince(cal.id, null) to true
            }
            val incoming = page.changed.filter { inWindow(it, from, to) }
            val incomingRefs = page.changed.map { it.ref }.toSet()
            val removedRefs = buildSet {
                addAll(page.deletedRefs.filter { it in previous })
                if (full) addAll(previous.keys.filter { it !in incomingRefs })
            }
            // Events that moved out of the window are dropped from the cache too.
            val leftWindow = page.changed.filter { !inWindow(it, from, to) && it.ref in previous }.map { it.ref }

            for (e in incoming) {
                val old = previous[e.ref] ?: continue
                if (old.etag == e.etag) continue
                if (old.isPlannerBlock && old.time != e.time) moved += old to e
            }
            removedRefs.mapNotNull(previous::get).filter { it.isPlannerBlock }.forEach { deleted += it }

            val upserts = incoming.filter { previous[it.ref] != it }
            if (upserts.isNotEmpty()) { snapshots.upsert(upserts); any = true }
            val removals = (removedRefs + leftWindow).toList()
            if (removals.isNotEmpty()) { snapshots.remove(removals); any = true }
            snapshots.setSyncToken(cal.id, page.nextSyncToken?.let { "$today|$it" })
        }
        return Pulled(any, UserEdits(moved, deleted))
    }

    private fun decode(stored: String, today: String): String? {
        val day = stored.substringBefore('|', "")
        val token = stored.substringAfter('|', "")
        return token.takeIf { day == today && it.isNotEmpty() }
    }

    private fun inWindow(e: EventSnapshot, from: LocalDate, to: LocalDate): Boolean {
        val (s, end) = when (val t = e.time) {
            is EventTime.Timed -> t.start.toLocalDate() to t.end.toLocalDate().plusDays(1)
            is EventTime.AllDay -> t.start to t.endExclusive
        }
        return s.isBefore(to) && end.isAfter(from)
    }
}
