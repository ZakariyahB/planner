package app.planner.engine.schedule

import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.Tier
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * newplan 5.3: the work window of one day and its tiers, all boundaries from [Settings].
 *
 * | Tier | Window |
 * |---|---|
 * | 0 normal | wake to the end of the Maghrib slot (or [Settings.normalEnd] if that is earlier, or if there is no Maghrib) |
 * | 1 rest | Maghrib slot end to normal end (empty when Maghrib is after normal end, as in summer) |
 * | 2 extended | normal end to extended end |
 * | 3 over the soft limit | extended end to the hard cap |
 *
 * Windows are half open, so a block ending exactly at a boundary does not touch the next tier. The Maghrib slot
 * itself counts as tier 0: a block may run through it (with the prayer buffer) without using rest time. Boundaries
 * are made monotonic (a later boundary is never before an earlier one), so odd settings cannot produce negative
 * windows.
 */
data class TierWindows(
    val date: LocalDate,
    val wake: LocalDateTime,
    val tier0End: LocalDateTime,
    val normalEnd: LocalDateTime,
    val extendedEnd: LocalDateTime,
    val hardCap: LocalDateTime,
) {
    /** Latest end allowed for a block that stays within [tier]. */
    fun limit(tier: Tier): LocalDateTime = when (tier) {
        Tier.NORMAL -> tier0End
        Tier.REST -> normalEnd
        Tier.EXTENDED -> extendedEnd
        Tier.OVER_SOFT_LIMIT -> hardCap
    }

    /** The window of [tier] as `[from, to)`. */
    fun window(tier: Tier): Pair<LocalDateTime, LocalDateTime> = when (tier) {
        Tier.NORMAL -> wake to tier0End
        Tier.REST -> tier0End to normalEnd
        Tier.EXTENDED -> normalEnd to extendedEnd
        Tier.OVER_SOFT_LIMIT -> extendedEnd to hardCap
    }

    /** The highest tier `[start, end)` touches. A block past the hard cap counts as [Tier.OVER_SOFT_LIMIT]. */
    fun tierOf(start: LocalDateTime, end: LocalDateTime): Tier {
        val last = if (end.isAfter(start)) end else start.plusNanos(1)
        return when {
            !last.isAfter(tier0End) -> Tier.NORMAL
            !last.isAfter(normalEnd) -> Tier.REST
            !last.isAfter(extendedEnd) -> Tier.EXTENDED
            else -> Tier.OVER_SOFT_LIMIT
        }
    }

    /** Minutes of `[start, end)` inside [tier]'s window. */
    fun minutesIn(tier: Tier, start: LocalDateTime, end: LocalDateTime): Long {
        val (from, to) = window(tier)
        val s = maxOf(start, from)
        val e = minOf(end, to)
        return if (e.isAfter(s)) Duration.between(s, e).toMinutes() else 0
    }

    companion object {
        /** [anchors] are the day's resolved anchors (before absorption: a Maghrib inside a fixed event still bounds tier 0). */
        fun of(date: LocalDate, settings: Settings, anchors: List<PrayerAnchor>): TierWindows {
            val wake = date.atTime(settings.wakeTime)
            val normalEnd = maxOf(wake, date.atTime(settings.normalEnd))
            val extendedEnd = maxOf(normalEnd, date.atTime(settings.extendedEnd))
            val hardCap = maxOf(extendedEnd, date.atTime(settings.hardCap))
            val maghrib = anchors.filter { it.date == date && it.prayer == Prayer.MAGHRIB }.minOfOrNull { it.begin }
            val tier0End = if (maghrib == null) {
                normalEnd
            } else {
                date.atTime(maghrib).plusMinutes(settings.prayerSlotMin.toLong()).coerceIn(wake, normalEnd)
            }
            return TierWindows(date, wake, tier0End, normalEnd, extendedEnd, hardCap)
        }
    }
}
