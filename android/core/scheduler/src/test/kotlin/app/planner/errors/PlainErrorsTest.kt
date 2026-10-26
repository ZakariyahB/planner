package app.planner.errors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlainErrorsTest {
    @Test
    fun `a refused calendar token says to reconnect`() {
        for (code in listOf(401, 403)) {
            assertEquals("Google turned down the calendar access. Reconnect Google Calendar from Settings, then try again.", PlainErrors.calendarHttp(code))
        }
    }

    @Test
    fun `too many requests says to wait`() {
        assertTrue("Wait a few minutes" in PlainErrors.calendarHttp(429))
    }

    @Test
    fun `a Google outage says it is not the user's fault`() {
        for (code in listOf(500, 502, 503)) assertTrue("having trouble right now" in PlainErrors.calendarHttp(code), "$code")
    }

    @Test
    fun `other calendar errors keep the code for reporting but say what to do`() {
        assertEquals("Google Calendar sent back an error (code 418). Try again later.", PlainErrors.calendarHttp(418))
    }

    @Test
    fun `a missing Planner calendar push says the plan itself is safe`() {
        val text = PlainErrors.plannerCalendar(503)
        assertTrue(text.startsWith("Your plan is saved on this phone but not in Google Calendar yet."), text)
        assertTrue("having trouble right now" in text, text)
    }

    @Test
    fun `Google sign-in codes are turned into advice`() {
        assertTrue("package name and SHA-1" in PlainErrors.googleAuth(10))
        assertTrue("docs/SETUP.md" in PlainErrors.googleAuth(10))
        assertTrue("No connection" in PlainErrors.googleAuth(7))
        assertTrue("cancelled" in PlainErrors.googleAuth(12501))
        assertTrue("Play services" in PlainErrors.googleAuth(2))
        assertEquals("Google sign-in didn't work (code 999). Try again in a moment.", PlainErrors.googleAuth(999))
        assertEquals("Google sign-in didn't work. Try again in a moment.", PlainErrors.googleAuth(null))
    }

    @Test
    fun `the assistant not being set up names the setting and the guide`() {
        val text = PlainErrors.aiNotSetUp("WORKER_URL")
        assertTrue("WORKER_URL" in text && "android/local.properties" in text && "docs/SETUP.md" in text, text)
    }

    @Test
    fun `no message looks like a crash report`() {
        val all = (listOf(0, 200, 301, 400, 401, 403, 404, 410, 418, 429, 500, 502, 503).flatMap { listOf(PlainErrors.calendarHttp(it), PlainErrors.plannerCalendar(it)) } +
            listOf(1, 2, 7, 8, 10, 12501, 12500, 999).map { PlainErrors.googleAuth(it) } +
            listOf(PlainErrors.CALENDAR_OFFLINE, PlainErrors.CALENDAR_UNKNOWN, PlainErrors.HEALTH_READ_FAILED, PlainErrors.aiNotSetUp("X")))
        for (text in all) {
            assertTrue(text.endsWith("."), text)
            assertTrue(listOf("Exception", "null", "Error:", "at app.").none { it in text }, text)
        }
    }
}
