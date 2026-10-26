package app.planner.calendar

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CalendarHttpException(val code: Int, message: String) : Exception(message)

/** Minimal Google Calendar v3 REST client. Writes only ever target the app's own Planner calendar. */
@Singleton
class GoogleCalendarClient @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listCalendars(token: String): List<CalendarInfo> =
        CalendarJson.parseCalendarList(request("GET", "$BASE/users/me/calendarList?maxResults=250", token))

    suspend fun listEvents(token: String, calendarId: String, from: Instant, to: Instant): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$BASE/calendars/${enc(calendarId)}/events?singleEvents=true&orderBy=startTime&maxResults=250")
                append("&timeMin=${enc(from.toString())}&timeMax=${enc(to.toString())}")
                pageToken?.let { append("&pageToken=${enc(it)}") }
            }
            val page = CalendarJson.parseEvents(request("GET", url, token), calendarId)
            events += page.events
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return events
    }

    /** Creates a calendar owned by this app (needs the calendar.app.created scope) and returns its id. */
    suspend fun createCalendar(token: String, name: String, timeZone: String): String {
        val body = buildJsonObject {
            put("summary", name)
            put("timeZone", timeZone)
        }.toString()
        return idOf(request("POST", "$BASE/calendars", token, body))
    }

    suspend fun insertEvent(token: String, calendarId: String, title: String, start: Instant, end: Instant, blockId: Long): String =
        idOf(request("POST", "$BASE/calendars/${enc(calendarId)}/events", token, eventBody(title, start, end, blockId)))

    suspend fun updateEvent(token: String, calendarId: String, eventId: String, title: String, start: Instant, end: Instant, blockId: Long) {
        request("PUT", "$BASE/calendars/${enc(calendarId)}/events/${enc(eventId)}", token, eventBody(title, start, end, blockId))
    }

    suspend fun deleteEvent(token: String, calendarId: String, eventId: String) {
        try {
            request("DELETE", "$BASE/calendars/${enc(calendarId)}/events/${enc(eventId)}", token)
        } catch (e: CalendarHttpException) {
            if (e.code != 404 && e.code != 410) throw e // already gone is fine
        }
    }

    /** The private extended property is how a Planner-calendar event is traced back to its block. */
    private fun eventBody(title: String, start: Instant, end: Instant, blockId: Long): String = buildJsonObject {
        put("summary", title)
        put("start", buildJsonObject { put("dateTime", start.toString()) })
        put("end", buildJsonObject { put("dateTime", end.toString()) })
        put("extendedProperties", buildJsonObject { put("private", buildJsonObject { put("planner_block", blockId.toString()) }) })
    }.toString()

    private fun idOf(body: String): String =
        json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: throw CalendarHttpException(0, "Google Calendar returned no id")

    private suspend fun request(method: String, url: String, token: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw CalendarHttpException(code, "Google Calendar returned $code")
                text
            } finally {
                conn.disconnect()
            }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val BASE = "https://www.googleapis.com/calendar/v3"
    }
}
