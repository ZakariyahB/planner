package app.planner.engine.parse

import app.planner.engine.model.Prayer
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateTitlesTest {
    private fun spec(title: String): TemplateSpec = assertIs<TitleParse.Template>(TemplateTitles.parse(title)).spec

    private fun needsALook(title: String): String = assertIs<TitleParse.NeedsALook>(TemplateTitles.parse(title)).reason

    // ---- newplan section 6 examples ----

    @Test
    fun `PintOS 3h`() {
        assertEquals(TemplateSpec(order = 1, title = "PintOS", durationMin = 180), spec("1. PintOS (3h)"))
    }

    @Test
    fun `lecture with a from time`() {
        val s = spec("2. Lecture - OS (2h, from 15:00)")
        assertEquals("Lecture - OS", s.title)
        assertEquals(120, s.durationMin)
        assertEquals(LocalTime.of(15, 0), s.earliestStart)
        assertEquals(2, s.order)
    }

    @Test
    fun `homework after a prayer`() {
        val s = spec("3. Arabic hw (1h, after Asr)")
        assertEquals("Arabic hw", s.title)
        assertEquals(60, s.durationMin)
        assertEquals(Prayer.ASR, s.afterPrayer)
        assertNull(s.earliestStart)
    }

    @Test
    fun `weekday session qualifier`() {
        val s = spec("4. Lecture - SDE (1h, Fri's)")
        assertEquals("Lecture - SDE", s.title)
        assertEquals(DayOfWeek.FRIDAY, s.sessionOf)
    }

    @Test
    fun `curly apostrophe from a phone keyboard`() {
        assertEquals(DayOfWeek.MONDAY, spec("4. Lecture - CP2 (1h, Mon’s)").sessionOf)
    }

    // ---- durations ----

    @Test
    fun `duration forms`() {
        assertEquals(180, spec("1. A (3h)").durationMin)
        assertEquals(90, spec("1. A (1h30)").durationMin)
        assertEquals(90, spec("1. A (1h 30m)").durationMin)
        assertEquals(135, spec("1. A (2h15)").durationMin)
        assertEquals(45, spec("1. A (45m)").durationMin)
        assertEquals(45, spec("1. A (45 min)").durationMin)
        assertEquals(90, spec("1. A (1.5h)").durationMin)
        assertEquals(120, spec("1. A (2 hours)").durationMin)
        assertEquals(60, spec("1. A (1 hr)").durationMin)
    }

    @Test
    fun `durations format back in the same style`() {
        assertEquals("2h", Durations.format(120))
        assertEquals("1h30", Durations.format(90))
        assertEquals("45m", Durations.format(45))
        assertEquals("3h05", Durations.format(185))
        assertEquals("3h15", Durations.format(195))
    }

    @Test
    fun `out of range durations need a look`() {
        needsALook("1. A (0h)")
        needsALook("1. A (2m)")
        needsALook("1. A (13h)")
        needsALook("1. A (1h75)")
    }

    // ---- notes ----

    @Test
    fun `notes in any order and with semicolons`() {
        val s = spec("7. Lecture - ADA (2h; Thu's; from 17:00)")
        assertEquals(LocalTime.of(17, 0), s.earliestStart)
        assertEquals(DayOfWeek.THURSDAY, s.sessionOf)
    }

    @Test
    fun `time spellings`() {
        assertEquals(LocalTime.of(14, 0), spec("1. A (1h, from 14:00)").earliestStart)
        assertEquals(LocalTime.of(9, 30), spec("1. A (1h, from 9:30)").earliestStart)
        assertEquals(LocalTime.of(14, 30), spec("1. A (1h, from 14.30)").earliestStart)
        assertEquals(LocalTime.of(15, 0), spec("1. A (1h, from 3pm)").earliestStart)
        assertEquals(LocalTime.of(15, 30), spec("1. A (1h, from 3:30pm)").earliestStart)
        assertEquals(LocalTime.of(16, 0), spec("1. A (1h, at 16:00)").earliestStart)
        assertEquals(LocalTime.of(16, 0), spec("1. A (1h, after 16:00)").earliestStart)
        assertEquals(LocalTime.of(15, 0), spec("1. A (1h, from 15)").earliestStart)
    }

    @Test
    fun `a bare hour that could be am or pm is not guessed`() {
        needsALook("1. A (1h, from 3)")
    }

    @Test
    fun `every prayer and its spellings`() {
        assertEquals(Prayer.DHUHR, spec("1. A (1h, after Dhuhr)").afterPrayer)
        assertEquals(Prayer.DHUHR, spec("1. A (1h, after zuhr)").afterPrayer)
        assertEquals(Prayer.ASR, spec("1. A (1h, after asr)").afterPrayer)
        assertEquals(Prayer.MAGHRIB, spec("1. A (1h, after Maghrib)").afterPrayer)
        assertEquals(Prayer.ISHA, spec("1. A (1h, after Isha)").afterPrayer)
        assertEquals(Prayer.FAJR, spec("1. A (1h, after Fajr)").afterPrayer)
    }

    @Test
    fun `no split and due day`() {
        val s = spec("5. P&S CW (1h30, no split, due Fri)")
        assertEquals(false, s.splittable)
        assertEquals(DayOfWeek.FRIDAY, s.dueBy)
        assertEquals(true, spec("5. P&S CW (1h30)").splittable)
        assertEquals(DayOfWeek.WEDNESDAY, spec("5. X (1h, by Wednesday)").dueBy)
    }

    @Test
    fun `unknown notes need a look and say which`() {
        assertTrue(needsALook("3. Arabic hw (1h, after lunch)").contains("after lunch"))
        assertTrue(needsALook("3. Arabic hw (1h, maybe)").contains("maybe"))
    }

    // ---- shape ----

    @Test
    fun `brackets inside the title are kept`() {
        val s = spec("5. Quran class (Sh Anass) (30m)")
        assertEquals("Quran class (Sh Anass)", s.title)
        assertEquals(30, s.durationMin)
    }

    @Test
    fun `spacing and punctuation variants`() {
        assertEquals("PintOS", spec("1.PintOS(3h)").title)
        assertEquals("PintOS", spec("  12.   PintOS   ( 3h )  ").title)
        assertEquals(12, spec("12. PintOS (3h)").order)
        assertEquals("PintOS", spec("1) PintOS (3h)").title)
        assertEquals("Lecture - OS", spec("2. Lecture - OS - (2h)").title)
        assertEquals("P&S problem sheet w-1", spec("1. P&S problem sheet w-1 (2h)").title)
    }

    @Test
    fun `numbered titles without a duration need a look`() {
        needsALook("1. PintOS")
        needsALook("1. PintOS (soon)")
        needsALook("1. PintOS ()")
        needsALook("1. (3h)")
    }

    @Test
    fun `titles without a number are not templates`() {
        assertEquals(TitleParse.NotATemplate, TemplateTitles.parse("Bank holiday"))
        assertEquals(TitleParse.NotATemplate, TemplateTitles.parse("PintOS (3h)"))
        assertEquals(TitleParse.NotATemplate, TemplateTitles.parse("1.5h reading (1h)"))
        assertEquals(TitleParse.NotATemplate, TemplateTitles.parse(""))
    }
}
