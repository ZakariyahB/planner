package app.planner.gcal.rest

import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.Settings
import app.planner.gcal.CalendarException
import java.io.IOException
import java.net.URLDecoder
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GoogleCalendarRestGatewayTest {
    private val base = GoogleCalendarRestGateway.BASE

    /** Answers requests from a list of (method, url prefix) routes; records everything it was sent. */
    private class Script : HttpTransport {
        val sent = mutableListOf<HttpRequest>()
        val routes = mutableListOf<Triple<String, String, MutableList<HttpResponse>>>()
        var offline = false

        fun on(method: String, prefix: String, vararg responses: HttpResponse) { routes += Triple(method, prefix, responses.toMutableList()) }

        override suspend fun send(request: HttpRequest): HttpResponse {
            sent += request
            if (offline) throw IOException("no network")
            val url = URLDecoder.decode(request.url, "UTF-8")
            val route = routes.lastOrNull { (method, prefix, _) ->
                request.method == method && if (prefix.endsWith("?")) url.startsWith(prefix) else url.substringBefore('?') == prefix
            }
                ?: return HttpResponse(404, """{"error":{"code":404}}""")
            return if (route.third.size > 1) route.third.removeAt(0) else route.third.first()
        }
    }

    private val calendarList = """
        {"items":[
          {"id":"academic@group","summary":"Academic ","accessRole":"owner"},
          {"id":"lectures@group","summary":"Something","summaryOverride":"Lectures","accessRole":"writer"},
          {"id":"prayer@group","summary":"Prayer times","accessRole":"owner"},
          {"id":"me@gmail.com","summary":"me@gmail.com","accessRole":"owner"},
          {"id":"holidays","summary":"Holidays in United Kingdom","accessRole":"reader"}
        ]}
    """.trimIndent()

    private fun ok(body: String) = HttpResponse(200, body)

    private fun event(id: String, start: String, end: String, extra: String = "") =
        """{"id":"$id","status":"confirmed","summary":"PintOS","etag":"\"e1\"","updated":"2026-10-31T10:00:00.000Z",
            "start":{"dateTime":"$start","timeZone":"Europe/London"},"end":{"dateTime":"$end","timeZone":"Europe/London"}$extra}"""

    private val tokens = mutableListOf<Boolean>()

    private fun gateway(script: Script, settings: Settings = Settings()) = GoogleCalendarRestGateway(
        http = script,
        tokens = { force -> tokens += force; if (force) "fresh" else "old" },
        settings = { settings },
        today = { LocalDate.of(2026, 11, 1) },
        retryDelayMs = 0,
    ).also { script.on("GET", "$base/users/me/calendarList", ok(calendarList)) }

    @Test
    fun `calendar list maps names, overrides and write access`() = runTest {
        val cals = gateway(Script()).calendars().associateBy { it.id }
        assertEquals(CalendarCategory.ACADEMIC, cals.getValue("academic@group").category)
        assertTrue(cals.getValue("academic@group").writable)
        assertEquals(CalendarCategory.LECTURES, cals.getValue("lectures@group").category)
        assertTrue(cals.getValue("prayer@group").isPrayerTimes)
        assertFalse(cals.getValue("prayer@group").writable)
        assertFalse(cals.getValue("me@gmail.com").writable)
        assertFalse(cals.getValue("holidays").writable)
    }

    @Test
    fun `events convert to local London time in winter and summer`() = runTest {
        val s = Script()
        val g = gateway(s)
        s.on("GET", "$base/calendars/academic@group/events/a1", ok(event("a1", "2026-11-02T09:30:00Z", "2026-11-02T11:30:00Z")))
        s.on("GET", "$base/calendars/academic@group/events/a2", ok(event("a2", "2026-07-01T08:30:00Z", "2026-07-01T10:30:00Z")))
        assertEquals(EventTime.Timed(LocalDateTime.of(2026, 11, 2, 9, 30), LocalDateTime.of(2026, 11, 2, 11, 30)), g.event(EventRef("academic@group", "a1"))!!.time)
        assertEquals(EventTime.Timed(LocalDateTime.of(2026, 7, 1, 9, 30), LocalDateTime.of(2026, 7, 1, 11, 30)), g.event(EventRef("academic@group", "a2"))!!.time)
        val e = g.event(EventRef("academic@group", "a1"))!!
        assertEquals("Academic", e.calendarName)
        assertEquals("\"e1\"", e.etag)
    }

    @Test
    fun `all-day, organiser, recurrence and private tags are read`() = runTest {
        val s = Script()
        val g = gateway(s)
        s.on(
            "GET", "$base/calendars/academic@group/events/t1",
            ok("""{"id":"t1","summary":"1. PintOS (3h)","etag":"x","updated":"2026-10-31T10:00:00Z","start":{"date":"2026-11-03"},"end":{"date":"2026-11-04"},
                "recurringEventId":"series1","organizer":{"email":"teacher@school.org"},"extendedProperties":{"private":{"planner":"1","taskId":"t"}}}"""),
        )
        val e = g.event(EventRef("academic@group", "t1"))!!
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4)), e.time)
        assertTrue(e.organiserIsExternal)
        assertEquals("series1", e.recurringEventId)
        assertTrue(e.isPlannerBlock)
        assertEquals("t", e.taskId)
    }

    @Test
    fun `missing and cancelled events read as null`() = runTest {
        val s = Script()
        val g = gateway(s)
        s.on("GET", "$base/calendars/academic@group/events/gone", HttpResponse(410, "{}"))
        s.on("GET", "$base/calendars/academic@group/events/c", ok("""{"id":"c","status":"cancelled"}"""))
        assertNull(g.event(EventRef("academic@group", "gone")))
        assertNull(g.event(EventRef("academic@group", "c")))
        assertNull(g.event(EventRef("academic@group", "unknown")))
    }

    @Test
    fun `insert sends the client id, local times with the zone, and the tags`() = runTest {
        val s = Script()
        val g = gateway(s)
        s.on("POST", "$base/calendars/academic@group/events", ok(event("abc12", "2026-11-02T09:30:00Z", "2026-11-02T11:30:00Z")))
        val draft = EventDraft("academic@group", "PintOS", EventTime.Timed(LocalDateTime.of(2026, 11, 2, 9, 30), LocalDateTime.of(2026, 11, 2, 11, 30)), "Task: 2h.", mapOf("planner" to "1"))
        g.insert("academic@group", "abc12", draft)
        val body = GoogleJson.json.parseToJsonElement(s.sent.last { it.method == "POST" }.body!!).jsonObject
        assertEquals("abc12", body["id"]!!.jsonPrimitive.content)
        val start = body["start"]!!.jsonObject
        assertEquals("2026-11-02T09:30:00Z", start["dateTime"]!!.jsonPrimitive.content)
        assertEquals("Europe/London", start["timeZone"]!!.jsonPrimitive.content)
        assertEquals("1", body["extendedProperties"]!!.jsonObject["private"]!!.jsonObject["planner"]!!.jsonPrimitive.content)
        assertEquals("Bearer old", s.sent.last().headers["Authorization"])
    }

    @Test
    fun `summer times carry the BST offset`() {
        assertEquals("2026-07-01T09:30:00+01:00", GoogleJson.rfc3339(LocalDateTime.of(2026, 7, 1, 9, 30), java.time.ZoneId.of("Europe/London")))
    }

    @Test
    fun `a retried insert of an existing id is a no-op and a deleted one is restored`() = runTest {
        val s = Script()
        val g = gateway(s)
        val draft = EventDraft("academic@group", "PintOS", EventTime.Timed(LocalDateTime.of(2026, 11, 2, 9, 30), LocalDateTime.of(2026, 11, 2, 11, 30)))
        s.on("POST", "$base/calendars/academic@group/events", HttpResponse(409, "{}"))
        s.on("GET", "$base/calendars/academic@group/events/abc12", ok(event("abc12", "2026-11-02T09:30:00Z", "2026-11-02T11:30:00Z")))
        g.insert("academic@group", "abc12", draft)
        assertTrue(s.sent.none { it.method == "PUT" })

        s.on("GET", "$base/calendars/academic@group/events/abc12", ok(event("abc12", "2026-11-02T09:30:00Z", "2026-11-02T11:30:00Z").replace("confirmed", "cancelled")))
        s.on("PUT", "$base/calendars/academic@group/events/abc12", ok(event("abc12", "2026-11-02T09:30:00Z", "2026-11-02T11:30:00Z")))
        g.insert("academic@group", "abc12", draft)
        val put = GoogleJson.json.parseToJsonElement(s.sent.last { it.method == "PUT" }.body!!).jsonObject
        assertEquals("confirmed", put["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update replaces times and tags, turning an all-day template into a timed block`() = runTest {
        val s = Script()
        val g = gateway(s)
        val allDay = """{"id":"t1","summary":"1. PintOS (3h)","etag":"x","start":{"date":"2026-11-03"},"end":{"date":"2026-11-04"},"reminders":{"useDefault":false},"extendedProperties":{"shared":{"k":"v"}}}"""
        s.on("GET", "$base/calendars/academic@group/events/t1", ok(allDay))
        s.on("PUT", "$base/calendars/academic@group/events/t1", ok(event("t1", "2026-11-03T09:30:00Z", "2026-11-03T12:45:00Z")))
        val draft = EventDraft("academic@group", "PintOS", EventTime.Timed(LocalDateTime.of(2026, 11, 3, 9, 30), LocalDateTime.of(2026, 11, 3, 12, 45)), null, mapOf("planner" to "1"))
        g.patch(EventRef("academic@group", "t1"), draft, RecurrenceScope.THIS_INSTANCE)
        val put = GoogleJson.json.parseToJsonElement(s.sent.last { it.method == "PUT" }.body!!).jsonObject
        val start = put["start"]!!.jsonObject
        assertNull(start["date"])
        assertEquals("2026-11-03T09:30:00Z", start["dateTime"]!!.jsonPrimitive.content)
        assertEquals("PintOS", put["summary"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, put["description"])
        assertTrue("reminders" in put) // fields we do not own survive
        assertEquals("v", put["extendedProperties"]!!.jsonObject["shared"]!!.jsonObject["k"]!!.jsonPrimitive.content)
        assertEquals("1", put["extendedProperties"]!!.jsonObject["private"]!!.jsonObject["planner"]!!.jsonPrimitive.content)
    }

    @Test
    fun `moving to another calendar uses events move first`() = runTest {
        val s = Script()
        val g = gateway(s)
        s.on("GET", "$base/calendars/academic@group/events/t1", ok(event("t1", "2026-11-03T09:30:00Z", "2026-11-03T10:30:00Z")))
        s.on("POST", "$base/calendars/academic@group/events/t1/move", ok(event("t1", "2026-11-03T09:30:00Z", "2026-11-03T10:30:00Z")))
        s.on("GET", "$base/calendars/lectures@group/events/t1", ok(event("t1", "2026-11-03T09:30:00Z", "2026-11-03T10:30:00Z")))
        s.on("PUT", "$base/calendars/lectures@group/events/t1", ok(event("t1", "2026-11-03T09:30:00Z", "2026-11-03T10:30:00Z")))
        val draft = EventDraft("lectures@group", "PintOS", EventTime.Timed(LocalDateTime.of(2026, 11, 3, 9, 30), LocalDateTime.of(2026, 11, 3, 10, 30)))
        val written = g.patch(EventRef("academic@group", "t1"), draft, RecurrenceScope.THIS_INSTANCE)
        assertEquals(EventRef("lectures@group", "t1"), written.ref)
        val move = s.sent.single { it.url.contains("/move") }
        assertTrue(URLDecoder.decode(move.url, "UTF-8").endsWith("destination=lectures@group"))
    }

    @Test
    fun `whole series edits shift the series, this-and-following is refused`() = runTest {
        val s = Script()
        val g = gateway(s)
        s.on("GET", "$base/calendars/academic@group/events/inst_20261105", ok(event("inst_20261105", "2026-11-05T18:00:00Z", "2026-11-05T18:30:00Z", ""","recurringEventId":"inst"""")))
        s.on("GET", "$base/calendars/academic@group/events/inst", ok(event("inst", "2026-09-03T17:00:00Z", "2026-09-03T17:30:00Z", ""","recurrence":["RRULE:FREQ=WEEKLY"]""")))
        s.on("PUT", "$base/calendars/academic@group/events/inst", ok(event("inst", "2026-09-03T18:00:00Z", "2026-09-03T18:30:00Z")))
        val draft = EventDraft("academic@group", "Class", EventTime.Timed(LocalDateTime.of(2026, 11, 5, 19, 0), LocalDateTime.of(2026, 11, 5, 19, 30)))
        g.patch(EventRef("academic@group", "inst_20261105"), draft, RecurrenceScope.WHOLE_SERIES)
        val put = GoogleJson.json.parseToJsonElement(s.sent.last { it.method == "PUT" }.body!!).jsonObject
        // the series started 3 Sep 18:00 London (BST); moved an hour later like the instance
        assertEquals("2026-09-03T19:00:00+01:00", put["start"]!!.jsonObject["dateTime"]!!.jsonPrimitive.content)
        assertTrue("recurrence" in put)
        assertFailsWith<UnsupportedOperationException> { g.patch(EventRef("academic@group", "inst_20261105"), draft, RecurrenceScope.THIS_AND_FOLLOWING) }
    }

    @Test
    fun `writes to prayer times or read-only calendars never reach Google`() = runTest {
        val s = Script()
        val g = gateway(s)
        val draft = EventDraft("prayer@group", "x", EventTime.Timed(LocalDateTime.of(2026, 11, 3, 9, 30), LocalDateTime.of(2026, 11, 3, 10, 30)))
        assertFailsWith<CalendarException.ReadOnlyCalendar> { g.insert("prayer@group", "abcde", draft) }
        assertFailsWith<CalendarException.ReadOnlyCalendar> { g.delete(EventRef("holidays", "h1"), RecurrenceScope.THIS_INSTANCE) }
        assertTrue(s.sent.none { it.method != "GET" })
    }

    @Test
    fun `writable follows the user's settings`() = runTest {
        val s = Script()
        val g = gateway(s, Settings(writableCategories = setOf(CalendarCategory.LECTURES)))
        val draft = EventDraft("academic@group", "x", EventTime.Timed(LocalDateTime.of(2026, 11, 3, 9, 30), LocalDateTime.of(2026, 11, 3, 10, 30)))
        assertFailsWith<CalendarException.ReadOnlyCalendar> { g.insert("academic@group", "abcde", draft) }
    }

    @Test
    fun `delete of a missing event is NotFound, whole series deletes the series`() = runTest {
        val s = Script()
        val g = gateway(s)
        s.on("DELETE", "$base/calendars/academic@group/events/gone", HttpResponse(410, ""))
        assertFailsWith<CalendarException.NotFound> { g.delete(EventRef("academic@group", "gone"), RecurrenceScope.THIS_INSTANCE) }
        s.on("GET", "$base/calendars/academic@group/events/i_1", ok(event("i_1", "2026-11-05T18:00:00Z", "2026-11-05T18:30:00Z", ""","recurringEventId":"i"""")))
        s.on("DELETE", "$base/calendars/academic@group/events/i", HttpResponse(204, ""))
        g.delete(EventRef("academic@group", "i_1"), RecurrenceScope.WHOLE_SERIES)
        assertTrue(s.sent.last().url.endsWith("/events/i"))
    }

    @Test
    fun `incremental sync sends only the sync token and keeps the other parameters`() = runTest {
        val s = Script()
        val g = gateway(s)
        s.on(
            "GET", "$base/calendars/academic@group/events?",
            ok("""{"items":[${event("a1", "2026-11-02T09:30:00Z", "2026-11-02T11:30:00Z")}],"nextPageToken":"p2"}"""),
            ok("""{"items":[{"id":"gone","status":"cancelled"}],"nextSyncToken":"sync-2"}"""),
        )
        val page = g.changesSince("academic@group", "sync-1")
        assertEquals(listOf("a1"), page.changed.map { it.ref.eventId })
        assertEquals(listOf(EventRef("academic@group", "gone")), page.deletedRefs)
        assertEquals("sync-2", page.nextSyncToken)
        val urls = s.sent.filter { it.url.contains("/events?") }.map { URLDecoder.decode(it.url, "UTF-8") }
        assertEquals(2, urls.size)
        for (u in urls) {
            assertTrue("syncToken=sync-1" in u)
            assertTrue("singleEvents=true" in u && "showDeleted=true" in u)
            for (forbidden in listOf("timeMin", "timeMax", "orderBy", "q=", "iCalUID", "updatedMin", "privateExtendedProperty", "sharedExtendedProperty")) {
                assertFalse(forbidden in u, "$forbidden in $u")
            }
        }
        assertTrue("pageToken=p2" in urls[1])
    }

    @Test
    fun `full sync lists the rolling window, and 410 means the token is gone`() = runTest {
        val s = Script()
        val g = gateway(s)
        s.on("GET", "$base/calendars/academic@group/events?", ok("""{"items":[],"nextSyncToken":"s1"}"""))
        assertEquals("s1", g.changesSince("academic@group", null).nextSyncToken)
        val u = URLDecoder.decode(s.sent.last().url, "UTF-8")
        assertTrue("timeMin=2026-10-25T00:00:00+01:00" in u, u) // today -7 days, still BST
        assertTrue("timeMax=2026-12-07T00:00:00Z" in u, u) // today +35 days, inclusive
        s.on("GET", "$base/calendars/academic@group/events?", HttpResponse(410, "{}"))
        assertFailsWith<CalendarException.SyncTokenGone> { g.changesSince("academic@group", "old") }
    }

    @Test
    fun `a 401 refreshes the token once, a second 401 means sign in again`() = runTest {
        val s = Script()
        val g = gateway(s)
        s.on("GET", "$base/calendars/academic@group/events/a1", HttpResponse(401, ""), ok(event("a1", "2026-11-02T09:30:00Z", "2026-11-02T11:30:00Z")))
        g.event(EventRef("academic@group", "a1"))
        assertEquals(listOf("Bearer old", "Bearer fresh"), s.sent.filter { it.url.endsWith("/a1") }.map { it.headers["Authorization"] })
        s.on("GET", "$base/calendars/academic@group/events/a2", HttpResponse(401, ""))
        assertFailsWith<CalendarException.AuthExpired> { g.event(EventRef("academic@group", "a2")) }
    }

    @Test
    fun `token expiry from the authorizer passes through, network errors are Network`() = runTest {
        val s = Script()
        val expired = GoogleCalendarRestGateway(s, { throw CalendarException.AuthExpired() }, { Settings() }, { LocalDate.of(2026, 11, 1) })
        assertFailsWith<CalendarException.AuthExpired> { expired.calendars() }
        s.offline = true
        assertIs<CalendarException.Network>(runCatching { gateway(s).calendars() }.exceptionOrNull())
    }

    @Test
    fun `rate limits and server errors are retried`() = runTest {
        val s = Script()
        val g = gateway(s)
        s.on("GET", "$base/calendars/academic@group/events/a1", HttpResponse(503, ""), HttpResponse(429, ""), ok(event("a1", "2026-11-02T09:30:00Z", "2026-11-02T11:30:00Z")))
        assertEquals("a1", g.event(EventRef("academic@group", "a1"))!!.ref.eventId)
    }
}
