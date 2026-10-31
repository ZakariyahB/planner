package app.planner.engine.parse

import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrayerImportTest {
    private val mon = LocalDate.of(2026, 11, 2)

    @Test
    fun `event start is the anchor and the title names the prayer`() {
        assertEquals(
            PrayerAnchor(mon, Prayer.DHUHR, LocalTime.of(11, 49)),
            PrayerImport.anchor("Dhuhr", "Begins 11:49", mon, LocalTime.of(11, 49)),
        )
        assertEquals(Prayer.ASR, PrayerImport.anchor("Asr", "Begins 14:05", mon, LocalTime.of(14, 5))?.prayer)
        assertEquals(Prayer.MAGHRIB, PrayerImport.anchor("Maghrib begins", null, mon, LocalTime.of(16, 35))?.prayer)
        assertEquals(Prayer.ISHA, PrayerImport.anchor("ELM: Isha", null, mon, LocalTime.of(18, 3))?.prayer)
        assertEquals(Prayer.FAJR, PrayerImport.anchor("Fajr", null, mon, LocalTime.of(5, 40))?.prayer)
    }

    @Test
    fun `the event start wins over the description`() {
        assertEquals(LocalTime.of(11, 50), PrayerImport.anchor("Dhuhr", "Begins 11:49", mon, LocalTime.of(11, 50))?.begin)
    }

    @Test
    fun `all-day entries fall back to Begins in the description`() {
        assertEquals(LocalTime.of(14, 5), PrayerImport.anchor("Asr", "Begins 14:05\nJama'ah 14:30", mon, null)?.begin)
        assertEquals(LocalTime.of(14, 5), PrayerImport.anchor("Asr", "<b>Begins:</b> 14.05<br>Jama'ah 14:30", mon, null)?.begin)
        assertEquals(LocalTime.of(18, 3), PrayerImport.anchor("Isha 18:03", null, mon, null)?.begin)
    }

    @Test
    fun `begins parsing`() {
        assertEquals(LocalTime.of(11, 49), PrayerImport.begins("Begins 11:49"))
        assertEquals(LocalTime.of(11, 49), PrayerImport.begins("Jama'ah 12:30. Begins at 11:49"))
        assertEquals(LocalTime.of(16, 35), PrayerImport.begins("begin - 4:35pm"))
        assertNull(PrayerImport.begins("Jama'ah 12:30"))
        assertNull(PrayerImport.begins(null))
    }

    @Test
    fun `seconds are dropped`() {
        assertEquals(LocalTime.of(11, 49), PrayerImport.anchor("Dhuhr", null, mon, LocalTime.of(11, 49, 30))?.begin)
    }

    @Test
    fun `titles that name no prayer are skipped`() {
        assertNull(PrayerImport.anchor("Jumu'ah", "Begins 13:00", mon, LocalTime.of(13, 0)))
        assertNull(PrayerImport.anchor("Sunrise", null, mon, LocalTime.of(7, 0)))
        assertNull(PrayerImport.anchor("Asr", "no time here", mon, null))
    }

    @Test
    fun `prayer spellings`() {
        assertEquals(Prayer.DHUHR, Names.prayerIn("Zuhr"))
        assertEquals(Prayer.DHUHR, Names.prayerIn("dhuhur begins"))
        assertEquals(Prayer.ISHA, Names.prayerIn("Esha"))
        assertNull(Names.prayerIn("Asrar")) // whole words only
    }
}
