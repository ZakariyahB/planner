package app.planner.engine.codec

import app.planner.engine.model.NotificationPrefs
import app.planner.engine.model.Settings
import app.planner.engine.nudge.NudgeKind
import app.planner.engine.nudge.NudgePlanner
import app.planner.engine.nudge.NudgeSchedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Fields added from CHANGES.md requests (Stream G sleep need, Stream E prompt times). */
class SettingsAdditionsTest {
    private val custom = Settings(
        sleepNeedMin = 480,
        notifications = NotificationPrefs(
            nightBeforeReviewEnabled = false,
            sundayDeadlinesAt = LocalTime.of(18, 15),
            fajrPromptDay = DayOfWeek.SATURDAY,
            fajrPromptAt = LocalTime.of(10, 30),
        ),
    )

    @Test
    fun `new settings fields round-trip`() {
        assertEquals(custom, PlannerCodec.settings.decode(PlannerCodec.settings.encode(custom)))
    }

    @Test
    fun `old settings rows get the new defaults`() {
        val decoded = PlannerCodec.settings.decode("""{"wakeTime":"09:30","notifications":{"snoozeMin":5}}""")
        assertEquals(420, decoded.sleepNeedMin)
        assertEquals(DayOfWeek.SUNDAY, decoded.notifications.fajrPromptDay)
        assertEquals(LocalTime.of(19, 0), decoded.notifications.sundayDeadlinesAt)
    }

    @Test
    fun `unknown prompt day falls back to Sunday`() {
        val decoded = PlannerCodec.settings.decode("""{"notifications":{"fajrPromptDay":"FUNDAY"}}""")
        assertEquals(DayOfWeek.SUNDAY, decoded.notifications.fajrPromptDay)
    }

    @Test
    fun `nudge schedule follows the notification prefs`() {
        assertEquals(
            NudgeSchedule(deadlinesAt = LocalTime.of(18, 15), fajrDay = DayOfWeek.SATURDAY, fajrAt = LocalTime.of(10, 30), nightBeforeEnabled = false),
            NudgeSchedule.from(custom.notifications),
        )
        val kinds = NudgePlanner.next(LocalDate.of(2026, 11, 2).atTime(8, 0), custom).map { it.kind }.toSet()
        assertFalse(NudgeKind.NIGHT_BEFORE_REVIEW in kinds)
    }
}
