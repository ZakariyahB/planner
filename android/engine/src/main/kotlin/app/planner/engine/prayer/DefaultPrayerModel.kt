package app.planner.engine.prayer

import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.schedule.PrayerModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * newplan 5.1.
 *
 * - Anchors are the imported begin times plus the per-prayer offset ([Settings.prayerOffsetMin], default 0). No
 *   clamping to fixed windows.
 * - A date without an imported time for a prayer (Saturday and Sunday, since the Prayer times calendar covers weekdays
 *   only) is interpolated and marked [PrayerAnchor.interpolated]:
 *   - known days on both sides within [WINDOW_DAYS]: linear interpolation between the nearest one on each side;
 *   - known days on one side only (the usual weekend case, before next week is imported): the nearest known value
 *     moved by the day-to-day trend, which is the least-squares slope over up to [TREND_POINTS] nearest known days on
 *     that side;
 *   - nothing known within [WINDOW_DAYS]: no anchor (the engine notes the missing day).
 *   The arithmetic runs in UTC minutes of the day (prayer times follow the sun, not the wall clock), so a weekend
 *   with a BST/GMT change interpolates correctly. Results round half up to the minute.
 * - Friday has no Dhuhr (Jumu'ah is a fixed event); an imported Friday Dhuhr is dropped.
 * - Fajr is never returned: it is not a work-window anchor.
 */
object DefaultPrayerModel : PrayerModel {
    const val WINDOW_DAYS = 7
    const val TREND_POINTS = 5

    /** The prayers that bound work, in day order. */
    val WORK_PRAYERS: List<Prayer> = listOf(Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)

    fun hasAnchor(date: LocalDate, prayer: Prayer): Boolean =
        prayer != Prayer.FAJR && !(prayer == Prayer.DHUHR && date.dayOfWeek == DayOfWeek.FRIDAY)

    override fun resolve(dates: List<LocalDate>, imported: List<PrayerAnchor>, settings: Settings): List<PrayerAnchor> {
        val zone = settings.zone
        // First import per (prayer, date) wins, so the result does not depend on duplicates further down.
        val known: Map<Prayer, Map<LocalDate, PrayerAnchor>> = WORK_PRAYERS.associateWith { p ->
            val m = LinkedHashMap<LocalDate, PrayerAnchor>()
            imported.filter { it.prayer == p && hasAnchor(it.date, p) }.forEach { m.putIfAbsent(it.date, it) }
            m
        }
        val out = ArrayList<PrayerAnchor>()
        for (date in dates.distinct().sorted()) {
            for (p in WORK_PRAYERS) {
                if (!hasAnchor(date, p)) continue
                val offset = (settings.prayerOffsetMin[p] ?: 0).toLong()
                val exact = known.getValue(p)[date]
                if (exact != null) {
                    out += exact.copy(begin = exact.begin.plusMinutes(offset))
                    continue
                }
                val time = interpolate(date, known.getValue(p), zone) ?: continue
                out += PrayerAnchor(date, p, time.plusMinutes(offset), interpolated = true)
            }
        }
        return out.sortedWith(compareBy({ it.date }, { it.begin }, { it.prayer }))
    }

    override fun absorb(anchors: List<PrayerAnchor>, fixed: List<FixedEvent>): List<PrayerAnchor> =
        anchors.filter { a ->
            val at = a.date.atTime(a.begin)
            fixed.none { f -> !at.isBefore(f.start) && at.isBefore(f.end) }
        }

    private fun interpolate(target: LocalDate, known: Map<LocalDate, PrayerAnchor>, zone: ZoneId): LocalTime? {
        val t = target.toEpochDay()
        val before = known.values.filter { it.date.toEpochDay() in (t - WINDOW_DAYS) until t }.sortedByDescending { it.date }
        val after = known.values.filter { it.date.toEpochDay() in (t + 1)..(t + WINDOW_DAYS) }.sortedBy { it.date }
        val utc: Long = when {
            before.isNotEmpty() && after.isNotEmpty() -> {
                val (d1, u1) = point(before.first(), zone, t)
                val (d2, u2) = point(after.first(), zone, t)
                // u1 + (u2 - u1) * (0 - d1) / (d2 - d1), with d relative to the target.
                roundHalfUp(u1 * (d2 - d1) + (u2 - u1) * (0 - d1), d2 - d1)
            }
            before.isNotEmpty() || after.isNotEmpty() -> {
                val pts = (if (before.isNotEmpty()) before else after).take(TREND_POINTS).map { point(it, zone, t) }
                val (dn, un) = pts.first()
                val n = pts.size.toLong()
                val sx = pts.sumOf { it.first }
                val sy = pts.sumOf { it.second }
                val sxy = pts.sumOf { it.first * it.second }
                val sxx = pts.sumOf { it.first * it.first }
                val num = n * sxy - sx * sy
                val den = n * sxx - sx * sx
                // un + slope * (0 - dn), slope = num / den (0 when one point).
                if (den == 0L) un else roundHalfUp(un * den + num * (0 - dn), den)
            }
            else -> return null
        }
        val offsetMin = zone.rules.getOffset(target.atTime(NOON)).totalSeconds / 60
        val local = Math.floorMod(utc + offsetMin, MINUTES_PER_DAY)
        return LocalTime.of((local / 60).toInt(), (local % 60).toInt())
    }

    /** (day relative to the target, minutes of the day in UTC). */
    private fun point(a: PrayerAnchor, zone: ZoneId, target: Long): Pair<Long, Long> {
        val offsetMin = zone.rules.getOffset(a.date.atTime(NOON)).totalSeconds / 60
        val local = a.begin.hour * 60L + a.begin.minute
        return (a.date.toEpochDay() - target) to (local - offsetMin)
    }

    /** num / den rounded half up; den > 0. */
    private fun roundHalfUp(num: Long, den: Long): Long = Math.floorDiv(2 * num + den, 2 * den)

    private val NOON: LocalTime = LocalTime.NOON
    private const val MINUTES_PER_DAY = 24L * 60
}
