package app.planner.calendar

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarJsonTest {
    @Test
    fun `calendar list prefers summaryOverride and keeps colour`() {
        val body = """
            {"items":[
              {"id":"a","summary":"Primary","backgroundColor":"#9fe1e7"},
              {"id":"b","summary":"Original","summaryOverride":"Prayer times"},
              {"summary":"no id"}
            ]}
        """.trimIndent()
        val list = CalendarJson.parseCalendarList(body)
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertEquals("Prayer times", list[1].name)
        assertEquals("#9fe1e7", list[0].colorHex)
    }

    @Test
    fun `parses timed events with offsets, all-day events and the page token`() {
        val body = """
            {"nextPageToken":"next","items":[
              {"id":"1","summary":"BoL","start":{"dateTime":"2026-10-28T10:15:00+00:00"},"end":{"dateTime":"2026-10-28T12:45:00+00:00"}},
              {"id":"2","summary":"Halaqa","start":{"dateTime":"2026-10-28T15:00:00+01:00"},"end":{"dateTime":"2026-10-28T17:30:00+01:00"}},
              {"id":"3","summary":"Eid","start":{"date":"2026-10-29"},"end":{"date":"2026-10-30"}},
              {"id":"4","status":"cancelled","start":{"dateTime":"2026-10-28T10:00:00Z"},"end":{"dateTime":"2026-10-28T11:00:00Z"}},
              {"id":"5","start":{"dateTime":"2026-10-28T20:00:00Z"},"end":{"dateTime":"2026-10-28T21:00:00Z"}}
            ]}
        """.trimIndent()
        val page = CalendarJson.parseEvents(body, "main")
        assertEquals("next", page.nextPageToken)
        assertEquals(listOf("1", "2", "3", "5"), page.events.map { it.id })
        assertEquals(EventTiming.Timed(Instant.parse("2026-10-28T10:15:00Z"), Instant.parse("2026-10-28T12:45:00Z")), page.events[0].timing)
        assertEquals(Instant.parse("2026-10-28T14:00:00Z"), (page.events[1].timing as EventTiming.Timed).start)
        assertEquals(EventTiming.AllDay(LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-30")), page.events[2].timing)
        assertEquals("(no title)", page.events[3].title)
        assertEquals("main", page.events[0].calendarId)
    }

    @Test
    fun `empty response has no items and no token`() {
        val page = CalendarJson.parseEvents("""{"kind":"calendar#events"}""", "main")
        assertEquals(emptyList(), page.events)
        assertNull(page.nextPageToken)
    }
}
