package app.planner.engine.schedule

import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.Placement
import app.planner.engine.model.Settings
import app.planner.engine.prayer.DefaultPrayerModel
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * newplan 13.3: plan 2-8 November and compare with the reference blocks. Comparison, not strict equality: every
 * invariant must hold, and every difference from [ReferenceWeek.referencePlacements] is listed here and explained in
 * docs/redesign/notes/A.md. The engine's own output is pinned too, so any change to it is deliberate.
 */
class ReferenceWeekPlanTest {
    private val request = PlanRequest(ReferenceWeek.days, ReferenceWeek.tasks, ReferenceWeek.fixed, ReferenceWeek.anchors, Settings())
    private val result = Engine.planning.plan(request)
    private val titles = ReferenceWeek.tasks.associate { it.id to it.title }

    private fun row(p: Placement): String = listOfNotNull(
        p.start.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
        "${p.start.toLocalTime()}-${p.end.toLocalTime()}",
        titles.getValue(p.taskId),
        p.label,
        if (p.prayerBufferMin > 0) "+${p.prayerBufferMin} ${p.coveredPrayers.joinToString("/") { it.name.lowercase() }}" else null,
        p.tier.name,
    ).joinToString(" ")

    @Test fun `every invariant holds`() {
        assertEquals(emptyList(), PlanInvariants.check(request, result))
    }

    @Test fun `everything fits - no overflow, every task on its own day, PintOS 8h`() {
        assertTrue(result.overflow.isEmpty(), "${result.overflow}")
        ReferenceWeek.tasks.forEach { t ->
            val ps = result.placements.filter { it.taskId == t.id }
            assertTrue(ps.isNotEmpty(), t.id)
            assertTrue(ps.all { it.start.toLocalDate() == t.assignedDay }, t.id)
            assertEquals(t.durationMin, ps.sumOf { Duration.between(it.start, it.end).toMinutes().toInt() - it.prayerBufferMin }, t.id)
        }
        val pintos = ReferenceWeek.tasks.filter { it.title == "PintOS" }.map { it.id }.toSet()
        assertEquals(480, result.placements.filter { it.taskId in pintos }.sumOf { Duration.between(it.start, it.end).toMinutes().toInt() - it.prayerBufferMin })
    }

    @Test fun `the assumed Halaqa and Jumu'ah prep gaps are reported`() {
        assertEquals(
            listOf<PlanWarning>(PlanWarning.AssumedGapKept(ReferenceWeek.days[2], "Halaqa"), PlanWarning.AssumedGapKept(ReferenceWeek.days[4], "Jumu'ah prep")),
            result.warnings,
        )
    }

    @Test fun `days over 18h00 are the same as the reference`() {
        fun over(ps: List<Placement>) = ps.filter { it.end.toLocalTime().isAfter(Settings().normalEnd) }.map { it.start.dayOfWeek }.toSet()
        assertEquals(over(ReferenceWeek.referencePlacements), over(result.placements))
    }

    @Test fun `the engine's plan for the reference week`() {
        val expected = listOf(
            "Mon 09:30-11:30 P&S problem sheet NORMAL",
            "Mon 11:30-12:45 Lecture - CP2 +15 dhuhr NORMAL",
            "Mon 14:00-15:15 Lecture - P&S +15 asr NORMAL",
            "Mon 17:00-19:15 Lecture - ADA +15 isha EXTENDED",
            "Tue 09:30-12:45 PintOS +15 dhuhr NORMAL",
            "Tue 14:00-15:15 Lecture - SDE +15 asr NORMAL",
            "Tue 15:15-16:15 Arabic hw NORMAL",
            "Tue 18:00-19:15 Lecture - OS +15 isha EXTENDED",
            "Wed 09:30-10:15 OS worksheet (1/2) NORMAL",
            "Wed 12:45-14:00 OS worksheet (2/2) NORMAL",
            "Wed 16:30-18:30 P&S CW +30 maghrib/isha EXTENDED",
            "Thu 09:30-12:45 SDE +15 dhuhr NORMAL",
            "Thu 15:00-17:15 Lecture - OS +15 maghrib REST",
            "Thu 19:00-20:00 Tutorial - ADA EXTENDED",
            "Fri 09:30-11:00 ADA worksheet NORMAL",
            "Fri 11:00-12:00 PintOS (1/2) NORMAL",
            "Fri 14:15-15:15 PintOS (2/2) NORMAL",
            "Fri 15:15-17:30 Lecture - P&S +15 maghrib REST",
            "Sat 09:30-11:30 P&S CW NORMAL",
            "Sat 11:30-12:45 Tutorial - P&S +15 dhuhr NORMAL",
            "Sat 12:45-13:45 Lecture - SDE NORMAL",
            "Sat 14:14-16:14 Arabic hw NORMAL",
            "Sun 09:30-11:45 PintOS (1/2) NORMAL",
            "Sun 13:15-14:15 PintOS (2/2) +15 asr NORMAL",
            "Sun 14:15-16:15 Internship apps NORMAL",
            "Sun 16:15-18:45 Catch-up + plan next week +30 maghrib/isha EXTENDED",
        )
        assertEquals(expected, result.placements.map(::row))
    }

    @Test fun `differences from the reference are exactly the explained ones`() {
        fun blocks(ps: List<Placement>) = ps.groupBy { it.taskId }.mapValues { (_, v) -> v.map { Triple(it.start, it.end, it.label) }.sortedBy { it.first } }
        val mine = blocks(result.placements)
        val ref = blocks(ReferenceWeek.referencePlacements)
        val differing = ReferenceWeek.tasks.map { it.id }.filter { mine[it] != ref[it] }
        println("Reference week: engine vs reference (newplan 13.3)")
        differing.forEach { id ->
            println("  ${titles.getValue(id)} [$id]")
            println("    reference: " + ReferenceWeek.referencePlacements.filter { it.taskId == id }.joinToString("; ") { row(it) })
            println("    engine:    " + result.placements.filter { it.taskId == id }.joinToString("; ") { row(it) })
        }
        // Mon, Tue, Thu and Fri match block for block. Each of these is explained in notes/A.md.
        assertEquals(
            listOf(
                "ps-cw-wed", "os-ws-wed", // Wed: packing, fewer chunks, starts at 16:30 at the Halaqa end
                "tut-ps-sat", "lec-sde-sat", "ps-cw-sat", // Sat: deep work earlier, passive later
                "arabic-hw-sat", // Sat: Asr slot ends 14:14, not 14:15
                "pintos-sun", "internship-sun", "catchup-sun", // Sun: largest first chunk; Internship stays in tier 0
            ),
            differing,
        )
    }

    @Test fun `with weekend anchors from the prayer model the plan is unchanged`() {
        // The model gives weekend Dhuhr 11:49 instead of the table's 11:48; nothing that week depends on that minute.
        val resolved = DefaultPrayerModel.resolve(ReferenceWeek.days, ReferenceWeek.importedAnchors, Settings())
        val modelled = Engine.planning.plan(request.copy(anchors = resolved))
        assertEquals(emptyList(), PlanInvariants.check(request.copy(anchors = resolved), modelled))
        assertEquals(result.placements, modelled.placements)
    }
}
