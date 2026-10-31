package app.planner.engine.schedule

import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.Tier
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TierWindowsTest {
    private val mon = ReferenceWeek.days[0]
    private fun at(s: String) = mon.atTime(LocalTime.parse(s))
    private val windows = TierWindows.of(mon, Settings(), ReferenceWeek.anchorsOn(mon))

    @Test fun `default boundaries - Maghrib 1635 so tier 0 runs to the end of its slot`() {
        assertEquals(at("09:30"), windows.wake)
        assertEquals(at("16:50"), windows.tier0End)
        assertEquals(at("18:00"), windows.normalEnd)
        assertEquals(at("20:00"), windows.extendedEnd)
        assertEquals(at("22:00"), windows.hardCap)
        assertEquals(listOf(at("16:50"), at("18:00"), at("20:00"), at("22:00")), Tier.entries.map(windows::limit))
    }

    @Test fun `the tier is the highest one a block touches, windows half open`() {
        assertEquals(Tier.NORMAL, windows.tierOf(at("15:00"), at("16:50")))
        assertEquals(Tier.REST, windows.tierOf(at("15:00"), at("16:51")))
        assertEquals(Tier.REST, windows.tierOf(at("17:00"), at("18:00")))
        assertEquals(Tier.EXTENDED, windows.tierOf(at("17:00"), at("19:15")))
        assertEquals(Tier.EXTENDED, windows.tierOf(at("19:00"), at("20:00")))
        assertEquals(Tier.OVER_SOFT_LIMIT, windows.tierOf(at("19:00"), at("20:01")))
        assertEquals(Tier.OVER_SOFT_LIMIT, windows.tierOf(at("21:00"), at("22:30")))
    }

    @Test fun `minutes per tier`() {
        assertEquals(10, windows.minutesIn(Tier.NORMAL, at("16:40"), at("19:15")))
        assertEquals(70, windows.minutesIn(Tier.REST, at("16:40"), at("19:15")))
        assertEquals(75, windows.minutesIn(Tier.EXTENDED, at("16:40"), at("19:15")))
        assertEquals(0, windows.minutesIn(Tier.OVER_SOFT_LIMIT, at("16:40"), at("19:15")))
    }

    @Test fun `summer - Maghrib after normal end leaves no rest zone`() {
        val w = TierWindows.of(mon, Settings(), listOf(PrayerAnchor(mon, Prayer.MAGHRIB, LocalTime.of(20, 50))))
        assertEquals(at("18:00"), w.tier0End)
        assertEquals(Tier.EXTENDED, w.tierOf(at("17:00"), at("18:30")))
    }

    @Test fun `no Maghrib anchor - tier 0 runs to normal end`() {
        assertEquals(at("18:00"), TierWindows.of(mon, Settings(), emptyList()).tier0End)
    }

    @Test fun `boundaries come from settings and are kept in order`() {
        val s = Settings(wakeTime = LocalTime.of(8, 0), normalEnd = LocalTime.of(17, 0), extendedEnd = LocalTime.of(16, 0), hardCap = LocalTime.of(21, 0), prayerSlotMin = 20)
        val w = TierWindows.of(mon, s, ReferenceWeek.anchorsOn(mon))
        assertEquals(at("08:00"), w.wake)
        assertEquals(at("16:55"), w.tier0End)
        assertEquals(at("17:00"), w.normalEnd)
        assertEquals(at("17:00"), w.extendedEnd) // 16:00 is before normal end, so the extended tier is empty
        assertEquals(at("21:00"), w.hardCap)
    }

    @Test fun `prayer offsets move the tier boundary with the anchor`() {
        val anchors = app.planner.engine.prayer.DefaultPrayerModel.resolve(
            listOf(mon), ReferenceWeek.importedAnchors, Settings(prayerOffsetMin = mapOf(Prayer.MAGHRIB to 10)),
        )
        assertEquals(at("17:00"), TierWindows.of(mon, Settings(), anchors).tier0End)
    }
}
