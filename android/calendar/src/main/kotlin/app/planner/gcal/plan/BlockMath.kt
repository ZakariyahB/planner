package app.planner.gcal.plan

import app.planner.engine.model.FixedEvent
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.Tier
import app.planner.engine.schedule.DayFlag
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** Small timing helpers shared by proposals and screen state. The engine does the real placing. */
object BlockMath {
    fun at(anchor: PrayerAnchor): LocalDateTime = anchor.date.atTime(anchor.begin)

    fun minutes(start: LocalDateTime, end: LocalDateTime): Int = Duration.between(start, end).toMinutes().toInt()

    /** Anchors a with start <= a < end (an anchor exactly at the end does not count). */
    fun covered(start: LocalDateTime, end: LocalDateTime, anchors: List<PrayerAnchor>): List<PrayerAnchor> =
        anchors.filter { val a = at(it); !a.isBefore(start) && a.isBefore(end) }.sortedBy { at(it) }

    /** Minutes of actual work in a block: its length minus one prayer slot per covered anchor. */
    fun workMinutes(start: LocalDateTime, end: LocalDateTime, anchors: List<PrayerAnchor>, slotMin: Int): Int =
        (minutes(start, end) - slotMin * covered(start, end, anchors).size).coerceAtLeast(0)

    /** newplan 5.1: a prayer whose anchor falls inside a fixed event is absorbed (no slot, no buffer). */
    fun absorb(anchors: List<PrayerAnchor>, fixed: List<FixedEvent>): List<PrayerAnchor> =
        anchors.filter { a -> fixed.none { f -> !at(a).isBefore(f.start) && at(a).isBefore(f.end) } }

    fun inPrayerSlot(time: LocalDateTime, anchors: List<PrayerAnchor>, slotMin: Int): PrayerAnchor? =
        anchors.firstOrNull { val a = at(it); !time.isBefore(a) && time.isBefore(a.plusMinutes(slotMin.toLong())) }

    fun overlaps(aStart: LocalDateTime, aEnd: LocalDateTime, bStart: LocalDateTime, bEnd: LocalDateTime): Boolean =
        aStart.isBefore(bEnd) && bStart.isBefore(aEnd)
}

/** newplan 5.3: the highest tier a block touches, judged by where it ends. Boundaries come from [Settings]. */
object Tiers {
    fun of(start: LocalDateTime, end: LocalDateTime, maghrib: LocalTime?, settings: Settings): Tier {
        if (end.toLocalDate().isAfter(start.toLocalDate())) return Tier.OVER_SOFT_LIMIT
        val t = end.toLocalTime()
        return when {
            t.isAfter(settings.extendedEnd) -> Tier.OVER_SOFT_LIMIT
            t.isAfter(settings.normalEnd) -> Tier.EXTENDED
            maghrib != null && t.isAfter(maghrib) -> Tier.REST
            else -> Tier.NORMAL
        }
    }

    fun dayFlags(tiers: Collection<Tier>, interpolatedPrayers: Boolean, hasOverflow: Boolean): Set<DayFlag> = buildSet {
        val max = tiers.maxOrNull() ?: Tier.NORMAL
        if (max >= Tier.REST) add(DayFlag.USES_REST_TIME)
        if (max >= Tier.EXTENDED) add(DayFlag.EXTENDED_DAY)
        if (max >= Tier.OVER_SOFT_LIMIT) add(DayFlag.OVER_SOFT_LIMIT)
        if (interpolatedPrayers) add(DayFlag.INTERPOLATED_PRAYERS)
        if (hasOverflow) add(DayFlag.HAS_OVERFLOW)
    }
}
