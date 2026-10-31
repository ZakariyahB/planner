package app.planner.engine.checkin

import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.EventRef
import app.planner.engine.model.Settings
import app.planner.engine.model.Tier
import app.planner.engine.ports.TodayBlock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** newplan 13.4: notification scheduler tests (reschedule on sync change, merged pings, quiet periods). */
class RescheduleTest {
    private val date: LocalDate = ReferenceWeek.monday
    private val anchors = ReferenceWeek.anchorsOn(date)
    private val settings = Settings()

    private fun block(id: String, from: String, to: String, title: String = id) = TodayBlock(
        ref = EventRef("cal", id), taskId = id, title = title, calendarName = "Academic", category = null,
        start = date.atTime(LocalTime.parse(from)), end = date.atTime(LocalTime.parse(to)), tier = Tier.NORMAL,
        prayerBufferMin = 0, coveredPrayers = emptyList(), isFixed = false,
    )

    private fun alarms(vararg blocks: TodayBlock) = CheckInPlanner.plan(date, blocks.toList(), anchors, settings).map { it.toAlarm() }

    private fun at(hm: String): LocalDateTime = date.atTime(LocalTime.parse(hm))

    @Test
    fun `first schedule sets every upcoming ping`() {
        val diff = AlarmDiff.between(emptyMap(), alarms(block("A", "09:30", "11:30")), now = at("08:00"))
        assertEquals(listOf("start:cal/A", "end:cal/A"), diff.set.map { it.id })
        assertEquals(emptySet(), diff.cancel)
        assertEquals(setOf("start:cal/A", "end:cal/A"), diff.remembered.keys)
    }

    @Test
    fun `nothing changed means nothing is set again`() {
        val first = AlarmDiff.between(emptyMap(), alarms(block("A", "09:30", "11:30")), at("08:00"))
        val second = AlarmDiff.between(first.remembered, alarms(block("A", "09:30", "11:30")), at("08:05"))
        assertEquals(emptyList(), second.set)
        assertEquals(emptySet(), second.cancel)
        assertEquals(setOf("start:cal/A", "end:cal/A"), second.unchanged)
    }

    @Test
    fun `a block moved in Google Calendar replaces its alarms instead of adding more`() {
        val first = AlarmDiff.between(emptyMap(), alarms(block("A", "09:30", "11:30")), at("08:00"))
        val moved = AlarmDiff.between(first.remembered, alarms(block("A", "12:05", "14:00")), at("08:10"))
        assertEquals(listOf("start:cal/A" to at("12:05"), "end:cal/A" to at("14:00")), moved.set.map { it.id to it.at })
        assertEquals(emptySet(), moved.cancel)
        assertEquals(2, moved.remembered.size)
    }

    @Test
    fun `a removed block has its alarms cancelled`() {
        val first = AlarmDiff.between(emptyMap(), alarms(block("A", "09:30", "11:30"), block("B", "14:30", "16:00")), at("08:00"))
        val after = AlarmDiff.between(first.remembered, alarms(block("B", "14:30", "16:00")), at("08:10"))
        assertEquals(setOf("start:cal/A", "end:cal/A"), after.cancel)
        assertEquals(emptyList(), after.set)
    }

    @Test
    fun `blocks that become back to back swap end and start for one Next up`() {
        val first = AlarmDiff.between(emptyMap(), alarms(block("A", "09:30", "10:30"), block("B", "10:40", "11:40")), at("08:00"))
        assertEquals(setOf("start:cal/A", "end:cal/A", "start:cal/B", "end:cal/B"), first.remembered.keys)
        val after = AlarmDiff.between(first.remembered, alarms(block("A", "09:30", "10:30"), block("B", "10:30", "11:30")), at("08:10"))
        assertEquals(setOf("end:cal/A", "start:cal/B"), after.cancel)
        assertEquals(setOf("next:cal/A", "end:cal/B"), after.set.map { it.id }.toSet())
        assertEquals(setOf("start:cal/A"), after.unchanged)
    }

    @Test
    fun `a renamed block is set again even at the same time`() {
        val first = AlarmDiff.between(emptyMap(), alarms(block("A", "09:30", "11:30")), at("08:00"))
        val renamed = AlarmDiff.between(first.remembered, alarms(block("A", "09:30", "11:30", title = "PintOS")), at("08:10"))
        assertEquals(setOf("start:cal/A", "end:cal/A"), renamed.set.map { it.id }.toSet())
    }

    @Test
    fun `pings already in the past are not replayed`() {
        val diff = AlarmDiff.between(emptyMap(), alarms(block("A", "09:30", "11:30")), now = at("10:00"))
        assertEquals(listOf("end:cal/A"), diff.set.map { it.id })
        assertEquals(setOf("end:cal/A"), diff.remembered.keys)
    }

    @Test
    fun `a ping at exactly now is past`() {
        val diff = AlarmDiff.between(emptyMap(), alarms(block("A", "09:30", "11:30")), now = at("09:30"))
        assertEquals(listOf("end:cal/A"), diff.set.map { it.id })
    }

    @Test
    fun `after a reboot or a clock change everything upcoming is set again`() {
        val first = AlarmDiff.between(emptyMap(), alarms(block("A", "09:30", "11:30")), at("08:00"))
        val forced = AlarmDiff.between(first.remembered, alarms(block("A", "09:30", "11:30")), at("08:10"), force = true)
        assertEquals(setOf("start:cal/A", "end:cal/A"), forced.set.map { it.id }.toSet())
        assertEquals(emptySet(), forced.unchanged)
    }

    @Test
    fun `a duplicated id in the plan is set once`() {
        val a = PlannedAlarm("x", at("10:00"))
        val diff = AlarmDiff.between(emptyMap(), listOf(a, a.copy(at = at("11:00"))), at("08:00"))
        assertEquals(listOf(a), diff.set)
    }

    @Test
    fun `a full fake day rescheduled through the day only ever shrinks`() {
        val blocks = listOf(block("A", "09:30", "11:30"), block("B", "11:30", "12:45"), block("C", "14:00", "15:15"))
        var remembered = emptyMap<String, String>()
        var t = at("07:00")
        val seen = mutableSetOf<String>()
        while (t.isBefore(at("16:00"))) {
            val d = AlarmDiff.between(remembered, CheckInPlanner.plan(date, blocks, anchors, settings).map { it.toAlarm() }, t)
            d.set.forEach { assertTrue(seen.add(it.id), "${it.id} set twice") }
            assertTrue(d.remembered.keys.all { it in remembered.keys || remembered.isEmpty() })
            remembered = d.remembered
            t = t.plusMinutes(30)
        }
        assertEquals(emptyMap(), remembered)
    }
}
