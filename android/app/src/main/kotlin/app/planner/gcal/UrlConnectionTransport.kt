package app.planner.gcal

import app.planner.gcal.rest.HttpRequest
import app.planner.gcal.rest.HttpResponse
import app.planner.gcal.rest.HttpTransport
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [HttpTransport] over HttpURLConnection, in the style of the legacy GoogleCalendarClient. GET, POST, PUT, DELETE. */
class UrlConnectionTransport : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val body = request.body
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}
