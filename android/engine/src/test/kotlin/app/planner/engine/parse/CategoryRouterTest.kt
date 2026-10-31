package app.planner.engine.parse

import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.CalendarCategory.ACADEMIC
import app.planner.engine.model.CalendarCategory.COMMUTE
import app.planner.engine.model.CalendarCategory.FITNESS
import app.planner.engine.model.CalendarCategory.LECTURES
import app.planner.engine.model.CalendarCategory.PERSONAL
import app.planner.engine.model.CalendarCategory.QURAN_ARABIC
import app.planner.engine.model.CalendarCategory.SOCIETY
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every example in docs/prompts/calendar-categories.md, plus the rules' own cases. */
class CategoryRouterTest {
    private fun check(expected: CalendarCategory, vararg titles: String) =
        titles.forEach { assertEquals(expected, CategoryRouter.route(it), "\"$it\"") }

    @Test
    fun academic() = check(
        ACADEMIC, "P&S problem sheet", "OS worksheet", "ADA worksheet", "CW", "P&S CW", "PintOS", "SDE",
        "ADA revision", "module revision", "OS homework",
    )

    @Test
    fun lectures() = check(LECTURES, "Lecture - CP2", "Tutorial - ADA", "Lecture - SDE", "Lecture - OS", "Tutorial - P&S", "catch up lectures")

    @Test
    fun `quran and arabic`() = check(QURAN_ARABIC, "Arabic hw", "Quran revision", "Arabic revision", "Qur’an memorisation", "Arabic lesson", "hifz")

    @Test
    fun commute() = check(COMMUTE, "kahf", "quran rev on commute", "Commute reading")

    @Test
    fun society() = check(SOCIETY, "STEMM Spotlight", "Jumu'ah prep", "Jumu’ah prep", "STEM Muslims social", "Society meeting", "ISoc committee")

    @Test
    fun fitness() = check(FITNESS, "Workout", "Gym", "Run", "Football", "Swimming")

    @Test
    fun personal() = check(
        PERSONAL, "Internship apps", "Catch-up + plan next week", "BoL", "Quran class (Sh Anass)", "Halaqa", "Meeting with Sam",
        "Interview", "vibe coding", "Dentist",
    )

    @Test
    fun `case and spacing do not matter`() = check(LECTURES, "LECTURE - os", "  tutorial-ada ")

    @Test
    fun `module names inside other words do not match`() = check(PERSONAL, "Pharmacy run errands".replace("run ", ""), "Sdesign chat")
}
