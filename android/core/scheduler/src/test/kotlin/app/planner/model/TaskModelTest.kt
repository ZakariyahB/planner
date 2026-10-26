package app.planner.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskModelTest {
    private val london = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 26)

    private fun at(date: String, time: String = "12:00") =
        LocalDate.parse(date).atTime(java.time.LocalTime.parse(time)).atZone(london).toInstant()

    @Test
    fun `task rejects blank title and out of range estimate`() {
        assertFailsWith<IllegalArgumentException> { Task(title = " ", estMin = 30) }
        assertFailsWith<IllegalArgumentException> { Task(title = "x", estMin = 4) }
        assertFailsWith<IllegalArgumentException> { Task(title = "x", estMin = 721) }
    }

    @Test
    fun `template round trips through json`() {
        val template = WeeklyTemplate(
            listOf(TemplateItem("lec", listOf(DayOfWeek.TUESDAY), "Lecture catch-up", "OS", 90, Anchor(Prayer.ASR, 15), Priority.HIGH)),
        )
        assertEquals(template, WeeklyTemplate.fromJson(template.toJson()))
    }

    @Test
    fun `expand places each item on its weekday, due end of day, local time`() {
        val template = WeeklyTemplate(
            listOf(
                TemplateItem("a", listOf(DayOfWeek.MONDAY), "Mon thing", estMin = 30),
                TemplateItem("b", listOf(DayOfWeek.SUNDAY), "Sun thing", estMin = 60),
            ),
        )
        val tasks = template.expand(monday, london)
        assertEquals(listOf("a@2026-10-26", "b@2026-11-01"), tasks.map { it.templateKey })
        assertEquals(at("2026-10-26", "23:59"), tasks[0].dueAt)
        assertEquals(at("2026-11-01", "23:59"), tasks[1].dueAt) // after the clocks go back on 25 Oct
        assertTrue(tasks.all { it.source == TaskSource.TEMPLATE && it.status == TaskStatus.OPEN })
    }

    @Test
    fun `expand skips keys that already exist`() {
        val template = WeeklyTemplate(listOf(TemplateItem("a", listOf(DayOfWeek.MONDAY), "Mon thing", estMin = 30)))
        assertTrue(template.expand(monday, london, existingKeys = setOf("a@2026-10-26")).isEmpty())
        assertEquals(1, template.expand(monday.plusWeeks(1), london, setOf("a@2026-10-26")).size)
    }

    @Test
    fun `expand requires a Monday`() {
        assertFailsWith<IllegalArgumentException> { WeeklyTemplate().expand(monday.plusDays(1), london) }
    }

    @Test
    fun `today shows due-today, overdue and undated open tasks, plus done-today`() {
        val today = LocalDate.of(2026, 10, 28)
        val tasks = listOf(
            Task(1, "due today", estMin = 30, dueAt = at("2026-10-28")),
            Task(2, "overdue", estMin = 30, dueAt = at("2026-10-26")),
            Task(3, "undated", estMin = 30),
            Task(4, "future", estMin = 30, dueAt = at("2026-10-30")),
            Task(5, "done today", estMin = 30, dueAt = at("2026-10-28"), status = TaskStatus.DONE),
            Task(6, "done earlier", estMin = 30, dueAt = at("2026-10-26"), status = TaskStatus.DONE),
            Task(7, "dropped", estMin = 30, dueAt = at("2026-10-28"), status = TaskStatus.DROPPED),
            Task(8, "partial", estMin = 30, dueAt = at("2026-10-27"), status = TaskStatus.PARTIAL),
        )
        val shown = tasksForToday(tasks, today, london).map { it.title }
        assertEquals(listOf("overdue", "partial", "due today", "undated", "done today"), shown)
    }

    @Test
    fun `same due time sorts by priority then title`() {
        val today = LocalDate.of(2026, 10, 28)
        val due = at("2026-10-28")
        val tasks = listOf(
            Task(1, "b low", estMin = 30, dueAt = due, priority = Priority.LOW),
            Task(2, "z high", estMin = 30, dueAt = due, priority = Priority.HIGH),
            Task(3, "a high", estMin = 30, dueAt = due, priority = Priority.HIGH),
        )
        assertEquals(listOf("a high", "z high", "b low"), tasksForToday(tasks, today, london).map { it.title })
    }
}
