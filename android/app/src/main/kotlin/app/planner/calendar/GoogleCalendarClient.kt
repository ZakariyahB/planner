package app.planner.calendar

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CalendarHttpException(val code: Int, message: String) : Exception(message)

/** Minimal Google Calendar v3 REST client (read only for M1). */
@Singleton
class GoogleCalendarClient @Inject constructor() {
    suspend fun listCalendars(token: String): List<CalendarInfo> =
        CalendarJson.parseCalendarList(get("$BASE/users/me/calendarList?maxResults=250", token))

    suspend fun listEvents(token: String, calendarId: String, from: Instant, to: Instant): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$BASE/calendars/${enc(calendarId)}/events?singleEvents=true&orderBy=startTime&maxResults=250")
                append("&timeMin=${enc(from.toString())}&timeMax=${enc(to.toString())}")
                pageToken?.let { append("&pageToken=${enc(it)}") }
            }
            val page = CalendarJson.parseEvents(get(url, token), calendarId)
            events += page.events
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return events
    }

    private suspend fun get(url: String, token: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw CalendarHttpException(code, "Google Calendar returned $code")
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val BASE = "https://www.googleapis.com/calendar/v3"
    }
}
