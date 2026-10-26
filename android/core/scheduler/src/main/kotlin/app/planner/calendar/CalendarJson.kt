package app.planner.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class EventsPage(val events: List<CalendarEvent>, val nextPageToken: String?)

/** Parses Google Calendar v3 REST responses (calendarList.list and events.list). */
object CalendarJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseCalendarList(body: String): List<CalendarInfo> =
        items(body).mapNotNull { c ->
            val id = c.str("id") ?: return@mapNotNull null
            CalendarInfo(
                id = id,
                name = c.str("summaryOverride") ?: c.str("summary") ?: id,
                colorHex = c.str("backgroundColor"),
            )
        }

    fun parseEvents(body: String, calendarId: String): EventsPage {
        val root = json.parseToJsonElement(body).jsonObject
        val events = items(body).mapNotNull { e ->
            if (e.str("status") == "cancelled") return@mapNotNull null
            val id = e.str("id") ?: return@mapNotNull null
            val timing = timing(e["start"]?.jsonObject, e["end"]?.jsonObject) ?: return@mapNotNull null
            val blockId = ((e["extendedProperties"] as? JsonObject)?.get("private") as? JsonObject)
                ?.str("planner_block")?.toLongOrNull()
            CalendarEvent(id, calendarId, e.str("summary") ?: "(no title)", timing, blockId)
        }
        return EventsPage(events, root.str("nextPageToken"))
    }

    private fun timing(start: JsonObject?, end: JsonObject?): EventTiming? {
        if (start == null || end == null) return null
        val startDateTime = start.str("dateTime")
        val endDateTime = end.str("dateTime")
        if (startDateTime != null && endDateTime != null) {
            return EventTiming.Timed(instant(startDateTime), instant(endDateTime))
        }
        val startDate = start.str("date")
        val endDate = end.str("date")
        if (startDate != null && endDate != null) {
            return EventTiming.AllDay(LocalDate.parse(startDate), LocalDate.parse(endDate))
        }
        return null
    }

    private fun instant(rfc3339: String): Instant = OffsetDateTime.parse(rfc3339).toInstant()

    private fun items(body: String): List<JsonObject> =
        json.parseToJsonElement(body).jsonObject["items"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
