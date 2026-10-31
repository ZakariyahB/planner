package app.planner.engine.nudge

import app.planner.engine.checkin.OpenTarget
import app.planner.engine.fakes.FakePlannerQueries
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.NotificationPrefs
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class NudgesTest {
    private val monday: LocalDate = ReferenceWeek.monday
    private val sunday: LocalDate = monday.plusDays(6)
    private val settings = Settings()

    private fun next(now: LocalDateTime, s: Settings = settings, schedule: NudgeSchedule = NudgeSchedule(), anchors: Boolean = true) =
        NudgePlanner.next(now, s, if (anchors) ReferenceWeek::anchorsOn else { _ -> emptyList() }, schedule)

    private fun List<Nudge>.byKind() = associateBy { it.kind }

    @Test
    fun `from Monday morning each prompt is at its next occurrence`() {
        val n = next(monday.atTime(8, 0)).byKind()
        assertEquals(monday.atTime(21, 30), n.getValue(NudgeKind.NIGHT_BEFORE_REVIEW).at)
        assertEquals(monday.plusDays(1), n.getValue(NudgeKind.NIGHT_BEFORE_REVIEW).forDate)
        assertEquals(sunday.atTime(19, 0), n.getValue(NudgeKind.SUNDAY_DEADLINES).at)
        assertEquals(sunday, n.getValue(NudgeKind.SUNDAY_DEADLINES).forDate)
        assertEquals(monday.plusDays(7), n.getValue(NudgeKind.WEEKLY_FAJR).forDate)
    }

    @Test
    fun `prompts come back sorted by time`() {
        val n = next(monday.atTime(8, 0))
        assertEquals(n.sortedBy { it.at }, n)
        assertEquals(3, n.size)
    }

    @Test
    fun `the Sunday midday Fajr prompt is moved out of the Dhuhr slot`() {
        // Sunday Dhuhr (interpolated) is 11:48, slot to 12:03.
        val fajr = next(monday.atTime(8, 0)).byKind().getValue(NudgeKind.WEEKLY_FAJR)
        assertEquals(sunday.atTime(12, 3), fajr.at)
        assertEquals(sunday.atTime(12, 0), next(monday.atTime(8, 0), anchors = false).byKind().getValue(NudgeKind.WEEKLY_FAJR).at)
    }

    @Test
    fun `after tonight's review has gone the next one is tomorrow night`() {
        val n = next(monday.atTime(21, 30)).byKind().getValue(NudgeKind.NIGHT_BEFORE_REVIEW)
        assertEquals(monday.plusDays(1).atTime(21, 30), n.at)
        assertEquals(monday.plusDays(2), n.forDate)
    }

    @Test
    fun `on Sunday evening after the deadlines prompt the next one is a week later`() {
        val n = next(sunday.atTime(19, 1)).byKind()
        assertEquals(sunday.plusDays(7).atTime(19, 0), n.getValue(NudgeKind.SUNDAY_DEADLINES).at)
    }

    @Test
    fun `Sunday prompts can be switched off`() {
        val kinds = next(monday.atTime(8, 0), settings.copy(notifications = NotificationPrefs(sundayPrompts = false))).map { it.kind }
        assertEquals(listOf(NudgeKind.NIGHT_BEFORE_REVIEW), kinds)
    }

    @Test
    fun `the night-before review can be switched off`() {
        val kinds = next(monday.atTime(8, 0), schedule = NudgeSchedule(nightBeforeEnabled = false)).map { it.kind }.toSet()
        assertEquals(setOf(NudgeKind.SUNDAY_DEADLINES, NudgeKind.WEEKLY_FAJR), kinds)
    }

    @Test
    fun `the review time is a setting and never later than the hard cap`() {
        val early = settings.copy(notifications = NotificationPrefs(nightBeforeReview = LocalTime.of(20, 15)))
        assertEquals(monday.atTime(20, 15), next(monday.atTime(8, 0), early).byKind().getValue(NudgeKind.NIGHT_BEFORE_REVIEW).at)
        val late = settings.copy(notifications = NotificationPrefs(nightBeforeReview = LocalTime.of(23, 0)))
        assertEquals(monday.atTime(22, 0), next(monday.atTime(8, 0), late).byKind().getValue(NudgeKind.NIGHT_BEFORE_REVIEW).at)
    }

    @Test
    fun `a prompt is not pushed past the hard cap by a prayer slot`() {
        val s = settings.copy(notifications = NotificationPrefs(nightBeforeReview = LocalTime.of(21, 55)))
        val anchors = { d: LocalDate -> listOf(PrayerAnchor(d, Prayer.ISHA, LocalTime.of(21, 50))) }
        val n = NudgePlanner.next(monday.atTime(8, 0), s, anchors).first { it.kind == NudgeKind.NIGHT_BEFORE_REVIEW }
        assertEquals(monday.atTime(21, 55), n.at)
    }

    @Test
    fun `ids are stable per kind and date`() {
        val a = next(monday.atTime(8, 0))
        val b = next(monday.atTime(9, 0))
        assertEquals(a.map { it.id }, b.map { it.id })
        assertTrue(a.any { it.id == "nudge:night_before_review:2026-11-03" })
    }

    @Test
    fun `the days are configurable`() {
        val n = next(monday.atTime(8, 0), schedule = NudgeSchedule(fajrDay = DayOfWeek.SATURDAY)).byKind().getValue(NudgeKind.WEEKLY_FAJR)
        assertEquals(monday.plusDays(5).atTime(12, 3), n.at) // Saturday 12:00 is inside the Dhuhr slot 11:48-12:03
        assertEquals(monday.plusDays(7), n.forDate)
    }

    // ---- What each prompt says when it fires ----

    @Test
    fun `deadlines prompt opens the chat`() {
        val n = next(monday.atTime(8, 0)).byKind().getValue(NudgeKind.SUNDAY_DEADLINES)
        val text = assertNotNull(NudgeTexts.forNudge(n, NudgeContext()))
        assertEquals("Any deadlines coming up?", text.title)
        assertEquals(OpenTarget.CHAT, text.open)
    }

    @Test
    fun `Fajr prompt is skipped when the week's Fajr is already known`() {
        val n = next(monday.atTime(8, 0)).byKind().getValue(NudgeKind.WEEKLY_FAJR)
        assertNull(NudgeTexts.forNudge(n, NudgeContext(fajrKnown = true)))
        val text = assertNotNull(NudgeTexts.forNudge(n, NudgeContext(fajrKnown = false)))
        assertEquals("Fajr this week?", text.title)
        assertTrue(text.body.startsWith("Roughly when is Fajr from Mon 9?"), text.body)
        assertEquals(OpenTarget.SETTINGS, text.open)
    }

    @Test
    fun `night-before review describes tomorrow`() {
        val tuesday = runBlocking { FakePlannerQueries({ monday.atTime(21, 30) }).week(monday).days[1].blocks }
        val n = next(monday.atTime(8, 0)).byKind().getValue(NudgeKind.NIGHT_BEFORE_REVIEW)
        val text = assertNotNull(NudgeTexts.forNudge(n, NudgeContext(tomorrow = tuesday, unscheduledTomorrow = 1)))
        assertEquals("5-minute review: tomorrow", text.title)
        assertEquals(
            "4 blocks from 09:30 (first: PintOS), done by 19:15. 1 task not planned yet. Take 5 minutes to decide roughly when each thing happens.",
            text.body,
        )
        assertEquals(OpenTarget.WEEK, text.open)
    }

    @Test
    fun `night-before review counts fixed events and pending approvals`() {
        val wednesday = runBlocking { FakePlannerQueries({ monday.atTime(21, 30) }).week(monday).days[2].blocks }
        val n = next(monday.atTime(8, 0)).byKind().getValue(NudgeKind.NIGHT_BEFORE_REVIEW)
        val text = assertNotNull(NudgeTexts.forNudge(n, NudgeContext(tomorrow = wednesday, pendingApprovals = 1)))
        assertEquals(
            "4 blocks from 09:30 (first: P&S CW (1/2)), done by 18:31. 2 fixed events. 1 change waiting for approval. " +
                "Take 5 minutes to decide roughly when each thing happens.",
            text.body,
        )
        assertEquals(OpenTarget.APPROVALS, text.open)
    }

    @Test
    fun `an empty tomorrow still gets its review`() {
        val n = next(monday.atTime(8, 0)).byKind().getValue(NudgeKind.NIGHT_BEFORE_REVIEW)
        assertEquals(
            "Nothing planned for tomorrow yet. Take 5 minutes to decide roughly when each thing happens.",
            NudgeTexts.forNudge(n, NudgeContext())?.body,
        )
    }

    // ---- Fajr quick reply ----

    @Test
    fun `Fajr times in the usual shapes`() {
        for (text in listOf("5:40", "05:40", "540", "5.40", "5:40am", " 5:40 AM ", "5h40")) {
            assertEquals(LocalTime.of(5, 40), FajrInput.parse(text), text)
        }
    }

    @Test
    fun `anything that is not a plausible Fajr is refused`() {
        for (text in listOf(null, "", "soon", "17:40", "5:4", "5:75", "12:00", "1:30")) {
            assertNull(FajrInput.parse(text), text.toString())
        }
    }
}
