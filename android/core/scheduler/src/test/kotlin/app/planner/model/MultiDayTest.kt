package app.planner.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiDayTest {
    private val london = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 26)

    private fun at(date: String, time: String = "23:59") =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(london).toInstant()

    @Test
    fun `one task becomes one per chosen day, each due at the end of its day`() {
        val base = Task(
            title = "Arabic", module = "AR", estMin = 45, startAt = LocalTime.of(19, 0),
            anchor = Anchor(Prayer.MAGHRIB, 10), priority = Priority.HIGH,
        )
        val tasks = base.onDates(listOf(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28)), london)
        assertEquals(listOf(at("2026-10-26"), at("2026-10-28"), at("2026-10-30")), tasks.map { it.dueAt })
        assertTrue(tasks.all { it.title == "Arabic" && it.module == "AR" && it.estMin == 45 })
        assertTrue(tasks.all { it.startAt == LocalTime.of(19, 0) && it.priority == Priority.HIGH && it.anchor == base.anchor })
        assertTrue(tasks.all { it.id == 0L && it.status == TaskStatus.OPEN })
    }

    @Test
    fun `repeated dates are collapsed and no dates gives no tasks`() {
        val base = Task(title = "x", estMin = 30)
        assertEquals(1, base.onDates(listOf(monday, monday), london).size)
        assertTrue(base.onDates(emptyList(), london).isEmpty())
    }

    @Test
    fun `weeks turn chosen weekdays into dates in order`() {
        val dates = Weeks.dates(monday, setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        assertEquals(listOf("2026-10-26", "2026-10-28", "2026-10-30"), dates.map { it.toString() })
    }

    @Test
    fun `a template item on several days makes a task on each, with a chosen time`() {
        val template = WeeklyTemplate(
            listOf(
                TemplateItem(
                    id = "arabic", days = listOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), title = "Arabic",
                    estMin = 45, startAt = LocalTime.of(19, 0),
                ),
            ),
        )
        val tasks = template.expand(monday, london)
        assertEquals(listOf("arabic@2026-10-26", "arabic@2026-10-28"), tasks.map { it.templateKey }.sortedBy { it })
        assertTrue(tasks.all { it.startAt == LocalTime.of(19, 0) && it.source == TaskSource.TEMPLATE })
    }

    @Test
    fun `generating a week again only adds the days that are new`() {
        val template = WeeklyTemplate(listOf(TemplateItem(id = "a", days = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), title = "A", estMin = 30)))
        val first = template.expand(monday, london)
        val keys = first.mapNotNull { it.templateKey }.toSet()
        assertEquals(2, first.size)
        assertTrue(template.expand(monday, london, existingKeys = keys).isEmpty())
        assertEquals(1, template.expand(monday, london, existingKeys = setOf("a@2026-10-26")).size)
    }

    @Test
    fun `a template saved before multi-day support still lands on its one day`() {
        val legacy = """{"items":[{"id":"old","day":"TUESDAY","title":"Old","estMin":30}]}"""
        val tasks = WeeklyTemplate.fromJson(legacy).expand(monday, london)
        assertEquals(listOf("old@2026-10-27"), tasks.map { it.templateKey })
    }

    @Test
    fun `template with days and a time round trips through json`() {
        val template = WeeklyTemplate(
            listOf(TemplateItem(id = "a", days = listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), title = "A", estMin = 30, startAt = LocalTime.of(7, 5))),
        )
        assertEquals(template, WeeklyTemplate.fromJson(template.toJson()))
    }
}
