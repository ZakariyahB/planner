package app.planner.gcal.rest

import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.model.EventRef
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A calendarList entry before it is mapped to a category. */
data class RawCalendar(val id: String, val name: String, val accessRole: String?)

data class CalendarListPage(val calendars: List<RawCalendar>, val nextPageToken: String?)

data class EventsPage(
    val events: List<EventSnapshot>,
    /** Cancelled entries (deleted events and deleted instances). */
    val cancelled: List<EventRef>,
    val nextPageToken: String?,
    val nextSyncToken: String?,
)

/**
 * Google Calendar v3 JSON to and from the engine's types. Times are converted at this boundary only: Google speaks
 * RFC 3339 instants, the engine local wall-clock time in [zone] (default Europe/London, so BST and GMT are handled by
 * java.time).
 */
object GoogleJson {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = true }
    private val RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    fun parseCalendarList(body: String): CalendarListPage {
        val root = json.parseToJsonElement(body).jsonObject
        val items = root.array("items").mapNotNull { c ->
            val id = c.str("id") ?: return@mapNotNull null
            if (c["deleted"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            RawCalendar(id, c.str("summaryOverride") ?: c.str("summary") ?: id, c.str("accessRole"))
        }
        return CalendarListPage(items, root.str("nextPageToken"))
    }

    fun parseEvents(body: String, calendarId: String, calendarName: String, zone: ZoneId): EventsPage {
        val root = json.parseToJsonElement(body).jsonObject
        val events = mutableListOf<EventSnapshot>()
        val cancelled = mutableListOf<EventRef>()
        for (e in root.array("items")) {
            val id = e.str("id") ?: continue
            if (e.str("status") == "cancelled") cancelled += EventRef(calendarId, id)
            else parseEvent(e, calendarId, calendarName, zone)?.let(events::add)
        }
        return EventsPage(events, cancelled, root.str("nextPageToken"), root.str("nextSyncToken"))
    }

    fun parseEvent(body: String, calendarId: String, calendarName: String, zone: ZoneId): EventSnapshot? =
        parseEvent(json.parseToJsonElement(body).jsonObject, calendarId, calendarName, zone)

    fun isCancelled(body: String): Boolean = json.parseToJsonElement(body).jsonObject.str("status") == "cancelled"

    fun parseEvent(e: JsonObject, calendarId: String, calendarName: String, zone: ZoneId): EventSnapshot? {
        if (e.str("status") == "cancelled") return null
        val id = e.str("id") ?: return null
        val time = time(e.obj("start"), e.obj("end"), zone) ?: return null
        val organizer = e.obj("organizer")
        val external = organizer != null && organizer["self"]?.jsonPrimitive?.booleanOrNull != true &&
            organizer.str("email")?.equals(calendarId, ignoreCase = true) != true
        val private = e.obj("extendedProperties")?.obj("private")
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        return EventSnapshot(
            ref = EventRef(calendarId, id),
            calendarName = calendarName,
            title = e.str("summary") ?: "(no title)",
            time = time,
            description = e.str("description"),
            etag = e.str("etag") ?: "",
            updated = e.str("updated")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
            recurringEventId = e.str("recurringEventId"),
            organiserIsExternal = external,
            privateProps = private,
        )
    }

    fun time(start: JsonObject?, end: JsonObject?, zone: ZoneId): EventTime? {
        if (start == null || end == null) return null
        val sdt = start.str("dateTime")
        val edt = end.str("dateTime")
        if (sdt != null && edt != null) return EventTime.Timed(local(sdt, zone), local(edt, zone))
        val sd = start.str("date")
        val ed = end.str("date")
        if (sd != null && ed != null) return EventTime.AllDay(LocalDate.parse(sd), LocalDate.parse(ed))
        return null
    }

    fun local(rfc3339: String, zone: ZoneId): LocalDateTime =
        OffsetDateTime.parse(rfc3339).atZoneSameInstant(zone).toLocalDateTime()

    fun rfc3339(t: LocalDateTime, zone: ZoneId): String = ZonedDateTime.of(t, zone).format(RFC3339)

    fun rfc3339(day: LocalDate, zone: ZoneId): String = rfc3339(day.atStartOfDay(), zone)

    /** start/end objects; the other form is set to null so a converted all-day event loses its date (and back). */
    fun timeFields(t: EventTime, zone: ZoneId): Pair<JsonObject, JsonObject> = when (t) {
        is EventTime.Timed -> timed(t.start, zone) to timed(t.end, zone)
        is EventTime.AllDay -> allDay(t.start) to allDay(t.endExclusive)
    }

    private fun timed(t: LocalDateTime, zone: ZoneId) = buildJsonObject {
        put("dateTime", rfc3339(t, zone)); put("timeZone", zone.id); put("date", JsonNull)
    }

    private fun allDay(d: LocalDate) = buildJsonObject {
        put("date", d.toString()); put("dateTime", JsonNull); put("timeZone", JsonNull)
    }

    /** A new event: [id] is the client-generated id. */
    fun newEvent(id: String, draft: EventDraft, zone: ZoneId): JsonObject {
        val (start, end) = timeFields(draft.time, zone)
        return buildJsonObject {
            put("id", id)
            put("summary", draft.title)
            draft.description?.let { put("description", it) }
            put("start", start)
            put("end", end)
            if (draft.privateProps.isNotEmpty()) put("extendedProperties", buildJsonObject { put("private", props(draft.privateProps)) })
        }
    }

    /**
     * The full event for a PUT: everything Google sent back, with title, description, times and the private
     * properties replaced (replacing, not merging, so undo can take the planner tags off again). Other fields
     * (attendees, reminders, colour, the shared properties) are kept as they were.
     */
    fun replaced(existing: JsonObject, draft: EventDraft, time: EventTime, zone: ZoneId, confirm: Boolean = false): JsonObject {
        val (start, end) = timeFields(time, zone)
        val out = existing.toMutableMap()
        out["summary"] = JsonPrimitive(draft.title)
        out["description"] = draft.description?.let(::JsonPrimitive) ?: JsonNull
        out["start"] = JsonObject(start.filterValues { it !is JsonNull })
        out["end"] = JsonObject(end.filterValues { it !is JsonNull })
        val ext = existing.obj("extendedProperties")?.toMutableMap() ?: mutableMapOf()
        ext["private"] = props(draft.privateProps)
        out["extendedProperties"] = JsonObject(ext)
        if (confirm) out["status"] = JsonPrimitive("confirmed")
        return JsonObject(out)
    }

    private fun props(map: Map<String, String>) = JsonObject(map.mapValues { JsonPrimitive(it.value) })

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): List<JsonObject> =
        (this[key])?.let { runCatching { it.jsonArray.map(JsonElement::jsonObject) }.getOrNull() }.orEmpty()
}
