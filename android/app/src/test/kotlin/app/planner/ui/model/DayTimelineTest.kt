package app.planner.ui.model

import app.planner.engine.fakes.FakePlannerQueries
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.Prayer
import app.planner.engine.ports.TodayState
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayTimelineTest {
    private val tuesday = ReferenceWeek.days[1]

    private fun today(at: LocalDateTime): TodayState = runBlocking { FakePlannerQueries(now = { at }).observeToday().first() }

    private fun build(at: LocalDateTime): DayTimeline {
        val s = today(at)
        return DayTimelines.build(s.date, s.blocks, s.anchors, at, normalEnd = LocalTime.of(18, 0))
    }

    @Test
    fun tuesdayMorningIsBeforeDhuhrWithPintosAsTheNowCard() {
        val t = build(tuesday.atTime(10, 40))
        assertEquals("Before Dhuhr", t.heading)
        assertEquals("Tuesday 3 November", t.dateLabel)
        assertEquals("1h 09m to Dhuhr at 11:49 · PintOS carries on after", t.subheading)
        val now = t.now!!
        assertEquals("PintOS", now.title)
        assertEquals("09:30 – 12:45", now.rangeLabel)
        assertEquals("3h of work, with 15 minutes for Dhuhr inside it", now.workLabel)
        assertEquals("Lecture - SDE", t.next!!.title)
    }

    @Test
    fun itemsAlternateStretchesAndPrayerMarkersInOrder() {
        val t = build(tuesday.atTime(10, 40))
        val keys = t.items.map { it.key }
        assertEquals(
            listOf("stretch-s0", "marker-DHUHR", "stretch-s1", "marker-ASR", "stretch-s2", "marker-MAGHRIB", "marker-ISHA", "stretch-s4"),
            keys,
        )
        val markers = t.items.filterIsInstance<TimelineItem.Marker>().map { it.marker }
        assertEquals(listOf(Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA), markers.map { it.prayer })
        assertTrue(markers.first().next)
        assertEquals("rest until 18:00", markers.first { it.prayer == Prayer.MAGHRIB }.note)
        assertEquals("16:33", markers.first { it.prayer == Prayer.MAGHRIB }.timeLabel)
    }

    @Test
    fun aBlockLivesInTheStretchHoldingMostOfIt() {
        val sections = build(tuesday.atTime(10, 40)).items.filterIsInstance<TimelineItem.Section>().map { it.stretch }
        val byTitle = sections.associate { it.title to it.blocks.map { b -> b.title } }
        assertEquals(listOf("PintOS"), byTitle["Before Dhuhr"])
        // Lecture - SDE starts at 14:00, four minutes before Asr, but most of it is after Asr.
        assertEquals(listOf("Lecture - SDE", "Arabic hw"), byTitle["After Asr"])
        assertEquals(listOf("Lecture - OS"), byTitle["After Isha"])
        val afterDhuhr = sections.first { it.title == "After Dhuhr" }
        assertTrue(afterDhuhr.blocks.isEmpty())
        assertEquals("PintOS until 12:45, then free till 14:00", afterDhuhr.note)
    }

    @Test
    fun extendedBlocksAreAmberAndTheirStretchIsMarkedExtended() {
        val sections = build(tuesday.atTime(10, 40)).items.filterIsInstance<TimelineItem.Section>().map { it.stretch }
        val afterIsha = sections.first { it.title == "After Isha" }
        assertTrue(afterIsha.extended)
        val os = afterIsha.blocks.single()
        assertEquals(BlockTone.EXTENDED, os.tone)
        assertEquals("Lectures · pauses for Isha · Extended day", os.detail)
        assertTrue(sections.first { it.title == "Before Dhuhr" }.current)
    }

    @Test
    fun betweenBlocksTheHeadingNamesTheStretchAndTheNextBlock() {
        val t = build(tuesday.atTime(13, 0))
        assertEquals("After Dhuhr", t.heading)
        assertNull(t.now)
        assertEquals("1h 04m to Asr at 14:04 · Next: Lecture - SDE at 14:00", t.subheading)
    }

    @Test
    fun afterTheLastPrayerNothingElseIsPlanned() {
        val t = build(tuesday.atTime(21, 0))
        assertEquals("After Isha", t.heading)
        assertEquals("Nothing else planned today", t.subheading)
        assertTrue(t.items.filterIsInstance<TimelineItem.Section>().map { it.stretch }.first { it.title == "Before Dhuhr" }.blocks.single().isPast)
    }

    @Test
    fun fridayHasNoDhuhrSoTheFirstStretchIsBeforeAsr() {
        val friday = ReferenceWeek.days[4]
        val t = build(friday.atTime(10, 0))
        assertEquals("Before Asr", t.heading)
        assertTrue(t.items.none { it is TimelineItem.Marker && it.marker.prayer == Prayer.DHUHR })
    }

    @Test
    fun weekendPrayerTimesShowAsEstimated() {
        val saturday = ReferenceWeek.days[5]
        val t = build(saturday.atTime(10, 0))
        val dhuhr = t.items.filterIsInstance<TimelineItem.Marker>().first().marker
        assertEquals("~11:48", dhuhr.timeLabel)
        assertTrue(dhuhr.interpolated)
    }

    @Test
    fun loggedOutcomesShowInTheDetailLine() {
        val at = tuesday.atTime(10, 40)
        val s = today(at)
        val pintos = s.blocks.first { it.title == "PintOS" }
        val log = ExecutionLogs.start(pintos, null, tuesday.atTime(9, 34))
        val t = DayTimelines.build(s.date, s.blocks, s.anchors, at, LocalTime.of(18, 0), logs = mapOf(log.id to log))
        assertEquals(ExecutionOutcome.STARTED, t.now!!.outcome)
        assertEquals("Academic · pauses for Dhuhr · started 09:34", t.now!!.detail)
    }

    @Test
    fun wednesdayFixedEventsAreLabelledFixed() {
        val wed = ReferenceWeek.days[2]
        val t = build(wed.atTime(11, 0))
        val bol = t.now!!
        assertEquals("BoL", bol.title)
        assertEquals("Personal · fixed", bol.detail)
        assertEquals("Fixed · until 12:45", bol.workLabel)
    }
}
