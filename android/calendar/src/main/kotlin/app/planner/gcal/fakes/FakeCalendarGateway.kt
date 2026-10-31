package app.planner.gcal.fakes

import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.EventRef
import app.planner.engine.ports.CalendarInfo
import app.planner.gcal.CalendarException
import app.planner.gcal.CalendarGateway
import app.planner.gcal.IncrementalPage
import java.time.Instant
import java.time.LocalDate

/**
 * In-memory Google Calendar with the same interface as the real client. Etags bump on every write; inserts with an
 * existing id are no-ops (idempotent retries). Stream B extends this with misbehaving variants for writer tests.
 */
open class FakeCalendarGateway(
    private val calendarList: List<CalendarInfo>,
    initial: List<EventSnapshot> = emptyList(),
    private val clock: () -> Instant = { Instant.EPOCH },
) : CalendarGateway {
    protected val events: MutableMap<EventRef, EventSnapshot> = initial.associateBy { it.ref }.toMutableMap()
    private var rev = 1
    var authExpired = false
    val writeLog = mutableListOf<String>()

    private fun check() { if (authExpired) throw CalendarException.AuthExpired() }

    private fun nameOf(calendarId: String) = calendarList.firstOrNull { it.id == calendarId }?.name ?: calendarId

    private fun requireWritable(calendarId: String) {
        if (calendarList.firstOrNull { it.id == calendarId }?.writable != true) throw CalendarException.ReadOnlyCalendar(calendarId)
    }

    protected fun snapshot(ref: EventRef, draft: EventDraft, recurring: String? = null) = EventSnapshot(
        ref = ref, calendarName = nameOf(ref.calendarId), title = draft.title, time = draft.time,
        description = draft.description, etag = "\"${++rev}\"", updated = clock(), recurringEventId = recurring,
        privateProps = draft.privateProps,
    )

    override suspend fun calendars(): List<CalendarInfo> { check(); return calendarList }

    override suspend fun events(from: LocalDate, toExclusive: LocalDate): List<EventSnapshot> {
        check()
        return events.values.filter { e ->
            val (s, end) = when (val t = e.time) {
                is EventTime.Timed -> t.start.toLocalDate() to t.end.toLocalDate().plusDays(1)
                is EventTime.AllDay -> t.start to t.endExclusive
            }
            s < toExclusive && end > from
        }.sortedBy { it.ref.eventId }
    }

    override suspend fun event(ref: EventRef): EventSnapshot? { check(); return events[ref] }

    override suspend fun insert(calendarId: String, eventId: String, draft: EventDraft): EventSnapshot {
        check(); requireWritable(calendarId)
        val ref = EventRef(calendarId, eventId)
        events[ref]?.let { return it }
        writeLog += "insert $eventId"
        return snapshot(ref, draft).also { events[ref] = it }
    }

    override suspend fun patch(ref: EventRef, draft: EventDraft, scope: RecurrenceScope): EventSnapshot {
        check(); requireWritable(ref.calendarId); requireWritable(draft.calendarId)
        val old = events[ref] ?: throw CalendarException.NotFound(ref)
        writeLog += "patch ${ref.eventId} $scope"
        events.remove(ref)
        val newRef = EventRef(draft.calendarId, ref.eventId)
        return snapshot(newRef, draft, old.recurringEventId).also { events[newRef] = it }
    }

    override suspend fun delete(ref: EventRef, scope: RecurrenceScope) {
        check(); requireWritable(ref.calendarId)
        events.remove(ref) ?: throw CalendarException.NotFound(ref)
        writeLog += "delete ${ref.eventId} $scope"
    }

    override suspend fun changesSince(calendarId: String, syncToken: String?): IncrementalPage {
        check()
        return IncrementalPage(events.values.filter { it.ref.calendarId == calendarId }, emptyList(), "token-$rev")
    }

    /** Simulates the user editing an event in Google Calendar between snapshot and write. */
    fun externalEdit(ref: EventRef, transform: (EventSnapshot) -> EventSnapshot) {
        val e = events.getValue(ref)
        events[ref] = transform(e).copy(etag = "\"${++rev}\"", updated = clock().plusSeconds(rev.toLong()))
    }
}
