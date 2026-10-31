package app.planner.engine.schedule

import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import java.time.LocalDateTime

/**
 * newplan 5.2, the core function.
 *
 * 1. A block never starts inside a prayer slot `[a, a + slot)`: such a start moves to the slot end (repeated, in case
 *    the slot end lands in another slot).
 * 2. The end grows by one slot for every anchor `a` with `start <= a < end`, iterated to a fixed point, so a buffer
 *    that pushes the end across another anchor adds that one too (the cascade). An anchor exactly at the end does not
 *    count. The loop terminates because the count only grows and is bounded by the number of anchors.
 * 3. If `[start, end)` overlaps a fixed event, the result is [BlockFit.Collides] with the earliest such event.
 *
 * Fajr anchors are ignored: Fajr is not a work-window anchor. Anchors are used as given, so absorbed prayers must
 * already be removed ([app.planner.engine.schedule.PrayerModel.absorb]).
 */
object DefaultBlockPlacer : BlockPlacer {
    override fun place(
        desiredStart: LocalDateTime,
        durationMin: Int,
        anchors: List<PrayerAnchor>,
        fixed: List<FixedEvent>,
        slotMin: Int,
    ): BlockFit {
        require(durationMin >= 0) { "durationMin must be >= 0, was $durationMin" }
        require(slotMin >= 0) { "slotMin must be >= 0, was $slotMin" }
        val times = anchors.asSequence()
            .filter { it.prayer != Prayer.FAJR }
            .map { it.prayer to it.date.atTime(it.begin) }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .toList()

        var start = desiredStart
        if (slotMin > 0) {
            while (true) {
                val slot = times.firstOrNull { (_, a) -> !start.isBefore(a) && start.isBefore(a.plusMinutes(slotMin.toLong())) }
                    ?: break
                start = slot.second.plusMinutes(slotMin.toLong())
            }
        }

        var end = start.plusMinutes(durationMin.toLong())
        var covered: List<Pair<Prayer, LocalDateTime>>
        while (true) {
            covered = times.filter { (_, a) -> !a.isBefore(start) && a.isBefore(end) }
            val newEnd = start.plusMinutes(durationMin.toLong() + slotMin.toLong() * covered.size)
            if (newEnd == end) break
            end = newEnd
        }

        val hit = fixed.asSequence()
            .filter { it.start.isBefore(end) && start.isBefore(it.end) }
            .minWithOrNull(compareBy<FixedEvent>({ it.start }, { it.end }, { it.ref.calendarId }, { it.ref.eventId }))
        if (hit != null) return BlockFit.Collides(start, end, hit)
        return BlockFit.Fits(start, end, slotMin * covered.size, covered.map { it.first })
    }
}
