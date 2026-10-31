package app.planner.gcal

import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.Placement
import app.planner.engine.model.Prayer
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.model.Tier
import app.planner.gcal.plan.Descriptions
import app.planner.gcal.plan.Tiers
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MappingAndIdsTest {
    private val all = CalendarCategory.entries.toSet()

    @Test
    fun `calendars are matched by trimmed case-insensitive name`() {
        val academic = CalendarMapping.info("a", "Academic ", "owner", all)
        assertEquals(CalendarCategory.ACADEMIC, academic.category)
        assertTrue(academic.writable)
        assertEquals("Academic", academic.name)
        assertEquals(CalendarCategory.QURAN_ARABIC, CalendarMapping.info("q", "quran AND arabic", "writer", all).category)
    }

    @Test
    fun `prayer times is never writable, other calendars neither`() {
        val prayer = CalendarMapping.info("p", " Prayer Times", "owner", all)
        assertTrue(prayer.isPrayerTimes)
        assertFalse(prayer.writable)
        assertNull(prayer.category)
        assertFalse(CalendarMapping.info("h", "Holidays in United Kingdom", "owner", all).writable)
    }

    @Test
    fun `writable needs the setting and a write role`() {
        assertFalse(CalendarMapping.info("f", "Fitness", "owner", all - CalendarCategory.FITNESS).writable)
        assertFalse(CalendarMapping.info("f", "Fitness", "reader", all).writable)
        assertFalse(CalendarMapping.info("f", "Fitness", "freeBusyReader", all).writable)
    }

    @Test
    fun `client event ids are valid base32hex and stable`() {
        val id = EventIds.from("cs-1/c-2")
        assertTrue(EventIds.isValid(id), id)
        assertEquals(26, id.length)
        assertEquals(id, EventIds.from("cs-1/c-2"))
        assertTrue(id != EventIds.from("cs-1/c-3"))
        repeat(200) { assertTrue(EventIds.isValid(EventIds.random())) }
        assertFalse(EventIds.isValid("abcd")) // too short
        assertFalse(EventIds.isValid("abcdwxyz")) // w-z are not base32hex
        assertFalse(EventIds.isValid("ABCDEF"))
    }

    @Test
    fun `descriptions follow the event conventions`() {
        val s = Settings()
        val day = LocalDate.of(2026, 11, 2)
        val lecture = Task("t", "Lecture - P&S", CalendarCategory.LECTURES, 120, earliestStart = LocalTime.of(14, 0))
        val p = Placement("t", start = day.atTime(14, 0), end = day.atTime(16, 15), prayerBufferMin = 15, coveredPrayers = listOf(Prayer.ASR))
        assertEquals("Task: 2h. Recording available from 14:00. Includes 15 min for Asr.", Descriptions.forBlock(lecture, p, s))
        val sat = Placement("t", 1, 2, LocalDateTime.of(2026, 11, 7, 17, 0), LocalDateTime.of(2026, 11, 7, 19, 30), 30, listOf(Prayer.MAGHRIB, Prayer.ISHA), Tier.EXTENDED, interpolatedPrayer = true)
        assertEquals(
            "Task: 2h, part 1 of 2. Recording available from 14:00. Includes 30 min for Maghrib and Isha. Extended hours (after 18:00). (Sat prayer time interpolated)",
            Descriptions.forBlock(lecture, sat, s),
        )
        assertEquals("Task: 1h. After Asr. Uses rest zone.", Descriptions.forBlock(Task("a", "Arabic hw", CalendarCategory.QURAN_ARABIC, 60, afterPrayer = Prayer.ASR), p.copy(prayerBufferMin = 0, coveredPrayers = emptyList(), tier = Tier.REST), s))
    }

    @Test
    fun `the user's own description text is kept below the planner's`() {
        assertEquals("Task: 1h.\n\nBring the textbook", Descriptions.merge("Task: 1h.", "Bring the textbook"))
        assertEquals("Task: 2h.\n\nBring the textbook", Descriptions.merge("Task: 2h.", "Task: 1h.\n\nBring the textbook"))
        assertEquals("Task: 2h.", Descriptions.merge("Task: 2h.", "Task: 1h. Includes 15 min for Asr."))
    }

    @Test
    fun `tiers by end time`() {
        val s = Settings()
        val d = LocalDate.of(2026, 11, 2)
        val maghrib = LocalTime.of(16, 35)
        assertEquals(Tier.NORMAL, Tiers.of(d.atTime(14, 0), d.atTime(15, 15), maghrib, s))
        assertEquals(Tier.NORMAL, Tiers.of(d.atTime(15, 0), d.atTime(16, 35), maghrib, s))
        assertEquals(Tier.REST, Tiers.of(d.atTime(15, 0), d.atTime(17, 15), maghrib, s))
        assertEquals(Tier.EXTENDED, Tiers.of(d.atTime(17, 0), d.atTime(19, 15), maghrib, s))
        assertEquals(Tier.EXTENDED, Tiers.of(d.atTime(19, 0), d.atTime(20, 0), maghrib, s))
        assertEquals(Tier.OVER_SOFT_LIMIT, Tiers.of(d.atTime(20, 0), d.atTime(21, 0), maghrib, s))
    }
}
