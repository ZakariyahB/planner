package app.planner.gcal.rest

import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.EventRef
import app.planner.engine.model.Settings
import app.planner.engine.ports.CalendarInfo
import app.planner.gcal.CalendarException
import app.planner.gcal.CalendarGateway
import app.planner.gcal.CalendarMapping
import app.planner.gcal.IncrementalPage
import app.planner.gcal.rest.GoogleJson.str
import java.io.IOException
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Google Calendar REST API v3 behind [CalendarGateway] (newplan 8). Pure Kotlin over [HttpTransport] and
 * [AccessTokens], so it is tested on the JVM; the app supplies HttpURLConnection and the Google authorization.
 *
 * - Reading: calendarList.list (names mapped to categories), events.list with singleEvents=true over a window.
 * - Incremental: [changesSince] sends syncToken with none of the parameters Google forbids alongside it (timeMin,
 *   timeMax, orderBy, q, iCalUID, updatedMin, privateExtendedProperty, sharedExtendedProperty) and keeps the others
 *   (singleEvents, showDeleted, maxResults) the same as the full listing. HTTP 410 is [CalendarException.SyncTokenGone].
 * - Writing: only to writable calendars (checked here too, before any request). Inserts carry the client id; a 409
 *   for an id that exists is a no-op (idempotent retry), and for a deleted event it is restored. Updates read the
 *   event and PUT it back with title, description, times and private properties replaced, so fields we do not own
 *   (attendees, reminders, colour) survive and planner tags can be taken off on undo. Moving between calendars uses
 *   events.move first.
 * - Recurring scope: this instance edits the instance; whole series edits the series (times shifted by the same
 *   amount, so the pattern stays). "This and following" needs the series split and is refused for now.
 * - 401 refreshes the token once; a second 401 is [CalendarException.AuthExpired]. 429 and 5xx are retried twice.
 */
class GoogleCalendarRestGateway(
    private val http: HttpTransport,
    private val tokens: AccessTokens,
    private val settings: suspend () -> Settings,
    private val today: () -> LocalDate,
    private val daysBack: Long = 7,
    private val daysAhead: Long = 35,
    private val retryDelayMs: Long = 800,
) : CalendarGateway {
    private val cacheLock = Mutex()
    private var raw: List<RawCalendar> = emptyList()

    // ---------------------------------------------------------------- reading

    override suspend fun calendars(): List<CalendarInfo> {
        val list = mutableListOf<RawCalendar>()
        var page: String? = null
        do {
            val url = "$BASE/users/me/calendarList?maxResults=250" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val parsed = GoogleJson.parseCalendarList(ok(get(url), null))
            list += parsed.calendars
            page = parsed.nextPageToken
        } while (page != null)
        cacheLock.withLock { raw = list }
        return map(list)
    }

    private suspend fun map(list: List<RawCalendar>): List<CalendarInfo> {
        val writable = settings().writableCategories
        return list.map { CalendarMapping.info(it.id, it.name, it.accessRole, writable) }
    }

    private suspend fun known(): List<CalendarInfo> {
        val cached = cacheLock.withLock { raw }
        return if (cached.isEmpty()) calendars() else map(cached)
    }

    private suspend fun nameOf(calendarId: String): String = known().firstOrNull { it.id == calendarId }?.name ?: calendarId

    private suspend fun zone(): ZoneId = settings().zone

    override suspend fun events(from: LocalDate, toExclusive: LocalDate): List<EventSnapshot> {
        val zone = zone()
        return known().flatMap { cal ->
            list(cal.id, cal.name, zone, "&timeMin=${enc(GoogleJson.rfc3339(from, zone))}&timeMax=${enc(GoogleJson.rfc3339(toExclusive, zone))}&showDeleted=false").events
        }
    }

    override suspend fun event(ref: EventRef): EventSnapshot? {
        val body = rawEvent(ref) ?: return null
        return GoogleJson.parseEvent(body, ref.calendarId, nameOf(ref.calendarId), zone())
    }

    /** The event's JSON (cancelled ones included), or null when Google does not know it. */
    private suspend fun rawEvent(ref: EventRef): JsonObject? {
        val r = get(eventUrl(ref.calendarId, ref.eventId))
        if (r.code == 404 || r.code == 410) return null
        return GoogleJson.json.parseToJsonElement(ok(r, ref)).jsonObject
    }

    override suspend fun changesSince(calendarId: String, syncToken: String?): IncrementalPage {
        val zone = zone()
        val (from, to) = today().minusDays(daysBack) to today().plusDays(daysAhead + 1)
        val params = if (syncToken != null) "&syncToken=${enc(syncToken)}"
        else "&timeMin=${enc(GoogleJson.rfc3339(from, zone))}&timeMax=${enc(GoogleJson.rfc3339(to, zone))}"
        val page = list(calendarId, nameOf(calendarId), zone, "&showDeleted=true$params", syncToken = syncToken)
        return IncrementalPage(page.events, page.cancelled, page.nextSyncToken)
    }

    private suspend fun list(calendarId: String, name: String, zone: ZoneId, params: String, syncToken: String? = null): EventsPage {
        val events = mutableListOf<EventSnapshot>()
        val cancelled = mutableListOf<EventRef>()
        var page: String? = null
        var next: String? = null
        do {
            val url = "$BASE/calendars/${enc(calendarId)}/events?singleEvents=true&maxResults=2500$params" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val r = get(url)
            if (r.code == 410) {
                if (syncToken != null) throw CalendarException.SyncTokenGone(calendarId)
                throw CalendarException.Network(IOException("Google Calendar returned 410"))
            }
            val parsed = GoogleJson.parseEvents(ok(r, null), calendarId, name, zone)
            events += parsed.events
            cancelled += parsed.cancelled
            page = parsed.nextPageToken
            next = parsed.nextSyncToken ?: next
        } while (page != null)
        return EventsPage(events, cancelled, null, next)
    }

    // ---------------------------------------------------------------- writing

    private suspend fun requireWritable(calendarId: String) {
        if (known().firstOrNull { it.id == calendarId }?.writable != true) throw CalendarException.ReadOnlyCalendar(calendarId)
    }

    override suspend fun insert(calendarId: String, eventId: String, draft: EventDraft): EventSnapshot {
        requireWritable(calendarId)
        val zone = zone()
        val ref = EventRef(calendarId, eventId)
        val r = send("POST", "$BASE/calendars/${enc(calendarId)}/events", GoogleJson.newEvent(eventId, draft, zone).toString())
        if (r.code == 409) {
            val existing = rawEvent(ref) ?: throw CalendarException.Network(IOException("Event id $eventId is taken"))
            if (existing.str("status") != "cancelled") return GoogleJson.parseEvent(existing, calendarId, nameOf(calendarId), zone)!!
            // A deleted event keeps its id; undo of a removal brings it back by confirming it again.
            val body = GoogleJson.replaced(existing, draft, draft.time, zone, confirm = true).toString()
            return parse(ok(send("PUT", eventUrl(calendarId, eventId), body), ref), calendarId, zone)
        }
        return parse(ok(r, ref), calendarId, zone)
    }

    override suspend fun patch(ref: EventRef, draft: EventDraft, scope: RecurrenceScope): EventSnapshot {
        requireWritable(ref.calendarId)
        requireWritable(draft.calendarId)
        if (scope == RecurrenceScope.THIS_AND_FOLLOWING) throw UnsupportedOperationException(THIS_AND_FOLLOWING)
        val zone = zone()
        val instance = rawEvent(ref) ?: throw CalendarException.NotFound(ref)
        val seriesId = if (scope == RecurrenceScope.WHOLE_SERIES) instance.str("recurringEventId") else null
        val targetId = seriesId ?: ref.eventId
        var calendarId = ref.calendarId
        if (draft.calendarId != ref.calendarId) {
            ok(send("POST", "${eventUrl(ref.calendarId, targetId)}/move?destination=${enc(draft.calendarId)}", null), ref)
            calendarId = draft.calendarId
        }
        val target = (if (seriesId != null) rawEvent(EventRef(calendarId, seriesId)) else rawEvent(EventRef(calendarId, targetId)))
            ?: throw CalendarException.NotFound(ref)
        val time = if (seriesId != null) shifted(target, instance, draft.time, zone) else draft.time
        val body = GoogleJson.replaced(target, draft, time, zone).toString()
        val written = parse(ok(send("PUT", eventUrl(calendarId, targetId), body), ref), calendarId, zone)
        return if (seriesId == null) written else event(EventRef(calendarId, ref.eventId)) ?: written
    }

    /** Whole-series edit: move the series by however far the instance moved, with the instance's new length. */
    private fun shifted(series: JsonObject, instance: JsonObject, wanted: EventTime, zone: ZoneId): EventTime {
        val seriesTime = GoogleJson.time(series["start"] as? JsonObject, series["end"] as? JsonObject, zone) ?: return wanted
        val instanceTime = GoogleJson.time(instance["start"] as? JsonObject, instance["end"] as? JsonObject, zone) ?: return wanted
        return when {
            seriesTime is EventTime.Timed && instanceTime is EventTime.Timed && wanted is EventTime.Timed -> {
                val start = seriesTime.start.plus(Duration.between(instanceTime.start, wanted.start))
                EventTime.Timed(start, start.plus(Duration.between(wanted.start, wanted.end)))
            }
            seriesTime is EventTime.AllDay && instanceTime is EventTime.AllDay && wanted is EventTime.AllDay -> {
                val start = seriesTime.start.plusDays(ChronoUnit.DAYS.between(instanceTime.start, wanted.start))
                EventTime.AllDay(start, start.plusDays(ChronoUnit.DAYS.between(wanted.start, wanted.endExclusive)))
            }
            else -> wanted
        }
    }

    override suspend fun delete(ref: EventRef, scope: RecurrenceScope) {
        requireWritable(ref.calendarId)
        if (scope == RecurrenceScope.THIS_AND_FOLLOWING) throw UnsupportedOperationException(THIS_AND_FOLLOWING)
        val id = if (scope == RecurrenceScope.WHOLE_SERIES) rawEvent(ref)?.str("recurringEventId") ?: ref.eventId else ref.eventId
        val r = send("DELETE", eventUrl(ref.calendarId, id), null)
        if (r.code == 404 || r.code == 410) throw CalendarException.NotFound(ref)
        ok(r, ref)
    }

    // ---------------------------------------------------------------- HTTP

    private suspend fun parse(body: String, calendarId: String, zone: ZoneId): EventSnapshot =
        GoogleJson.parseEvent(body, calendarId, nameOf(calendarId), zone)
            ?: throw CalendarException.Network(IOException("Google Calendar returned an unreadable event"))

    private suspend fun get(url: String) = send("GET", url, null)

    private suspend fun send(method: String, url: String, body: String?): HttpResponse {
        var attempt = 0
        var refreshed = false
        while (true) {
            val token = tokens.token(forceRefresh = refreshed)
            val headers = buildMap {
                put("Authorization", "Bearer $token")
                put("Accept", "application/json")
                if (body != null) put("Content-Type", "application/json; charset=utf-8")
            }
            val r = try {
                http.send(HttpRequest(method, url, body, headers))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw CalendarException.Network(e)
            }
            when {
                r.code == 401 && !refreshed -> refreshed = true
                r.code == 401 -> throw CalendarException.AuthExpired()
                (r.code == 429 || r.code >= 500 || rateLimited(r)) && attempt < 2 -> { attempt++; delay(retryDelayMs * attempt) }
                else -> return r
            }
        }
    }

    private fun rateLimited(r: HttpResponse) = r.code == 403 && (r.body.contains("rateLimitExceeded") || r.body.contains("userRateLimitExceeded"))

    private fun ok(r: HttpResponse, ref: EventRef?): String = when {
        r.code in 200..299 -> r.body
        r.code == 404 && ref != null -> throw CalendarException.NotFound(ref)
        r.code == 403 && ref != null -> throw CalendarException.ReadOnlyCalendar(ref.calendarId)
        else -> throw CalendarException.Network(IOException("Google Calendar returned ${r.code}"))
    }

    private fun eventUrl(calendarId: String, eventId: String) = "$BASE/calendars/${enc(calendarId)}/events/${enc(eventId)}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val BASE = "https://www.googleapis.com/calendar/v3"
        const val THIS_AND_FOLLOWING = "\"This and following events\" is not supported yet: choose this event or the whole series."
    }
}
