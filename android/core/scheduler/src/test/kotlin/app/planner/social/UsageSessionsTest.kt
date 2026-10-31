package app.planner.social

import kotlin.test.Test
import kotlin.test.assertEquals

class UsageSessionsTest {
    private val min = 60_000L
    private val from = 0L
    private val to = 24 * 60 * min
    private val insta = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val apps = setOf(insta, tiktok)

    private fun fg(pkg: String, atMin: Long) = UsageEvent(pkg, UsageEventType.FOREGROUND, atMin * min)

    private fun bg(pkg: String, atMin: Long) = UsageEvent(pkg, UsageEventType.BACKGROUND, atMin * min)

    private fun minutes(events: List<UsageEvent>, now: Long = to) = UsageSessions.minutesPerApp(events, apps, from, to, now)

    @Test
    fun `a session counts from foreground to background`() {
        assertEquals(mapOf(insta to 25), minutes(listOf(fg(insta, 600), bg(insta, 625))))
    }

    @Test
    fun `sessions of one app add up and apps are kept apart`() {
        val events = listOf(fg(insta, 100), bg(insta, 110), fg(tiktok, 200), bg(tiktok, 230), fg(insta, 300), bg(insta, 315))
        assertEquals(mapOf(insta to 25, tiktok to 30), minutes(events))
    }

    @Test
    fun `apps that are not social are ignored`() {
        assertEquals(emptyMap(), minutes(listOf(fg("com.android.chrome", 10), bg("com.android.chrome", 60))))
    }

    @Test
    fun `events are used in time order whatever order they arrive in`() {
        assertEquals(mapOf(insta to 10), minutes(listOf(bg(insta, 110), fg(insta, 100))))
    }

    @Test
    fun `a session still open now counts up to now`() {
        assertEquals(mapOf(insta to 40), minutes(listOf(fg(insta, 600)), now = 640 * min))
    }

    @Test
    fun `a session started before the window counts from the start of the window`() {
        assertEquals(mapOf(insta to 12), minutes(listOf(bg(insta, 12))))
    }

    @Test
    fun `a session running past the end of the window is cut off at it`() {
        assertEquals(mapOf(insta to 10), UsageSessions.minutesPerApp(listOf(fg(insta, 1430)), apps, from, to, nowMillis = to + 60 * min))
    }

    @Test
    fun `a second foreground without a background does not double count`() {
        assertEquals(mapOf(insta to 20), minutes(listOf(fg(insta, 100), fg(insta, 105), bg(insta, 120))))
    }

    @Test
    fun `a stray background with nothing open after a session adds nothing`() {
        assertEquals(mapOf(insta to 10), minutes(listOf(fg(insta, 100), bg(insta, 110), bg(insta, 111))))
    }

    @Test
    fun `short sessions add up before rounding`() {
        // six 10-second glances are a minute in total, not six rounded-up minutes or zero
        val events = (0 until 6).flatMap { i ->
            listOf(UsageEvent(insta, UsageEventType.FOREGROUND, 1_000_000L + i * 60_000L), UsageEvent(insta, UsageEventType.BACKGROUND, 1_010_000L + i * 60_000L))
        }
        assertEquals(mapOf(insta to 1), minutes(events))
    }

    @Test
    fun `the total is the sum over apps`() {
        assertEquals(55, UsageSessions.total(mapOf(insta to 25, tiktok to 30)))
        assertEquals(0, UsageSessions.total(emptyMap()))
    }

    @Test
    fun `apps with under half a minute of use are left out`() {
        assertEquals(emptyMap(), minutes(listOf(UsageEvent(insta, UsageEventType.FOREGROUND, 1_000_000), UsageEvent(insta, UsageEventType.BACKGROUND, 1_020_000))))
    }

    private fun screen(pkg: String, activity: String, atMin: Long) = UsageEvent(pkg, UsageEventType.FOREGROUND, atMin * min, activity)

    private fun leave(pkg: String, activity: String, atMin: Long) = UsageEvent(pkg, UsageEventType.BACKGROUND, atMin * min, activity)

    private fun screenOff(atMin: Long) = UsageEvent("android", UsageEventType.SCREEN_OFF, atMin * min)

    @Test
    fun `moving between the screens of one app is one session`() {
        // Reels opens before the feed has gone away: the feed's pause must not end the session early.
        val events = listOf(screen(insta, "Feed", 100), screen(insta, "Reels", 110), leave(insta, "Feed", 111), leave(insta, "Reels", 130))
        assertEquals(mapOf(insta to 30), minutes(events))
    }

    @Test
    fun `two screens of one app side by side are not counted twice`() {
        val events = listOf(screen(insta, "A", 100), screen(insta, "B", 100), leave(insta, "A", 120), leave(insta, "B", 120))
        assertEquals(mapOf(insta to 20), minutes(events))
    }

    @Test
    fun `the screen going off ends every open session`() {
        val events = listOf(fg(insta, 100), screenOff(115), fg(tiktok, 200), screenOff(210), bg(insta, 400))
        assertEquals(mapOf(insta to 15, tiktok to 10), minutes(events))
    }

    @Test
    fun `an app that was not open when the screen went off adds nothing`() {
        assertEquals(emptyMap(), minutes(listOf(screenOff(50))))
    }
}
