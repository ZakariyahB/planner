package app.planner.ui.model

import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.Tier
import app.planner.engine.ports.TodayBlock
import app.planner.engine.ports.WeekDay
import app.planner.engine.ports.WeekState
import app.planner.engine.schedule.DayFlag
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// The Week screen: one row per day with five stretches (wake to Dhuhr, to Asr, to Maghrib, to Isha, Isha to the
// hard cap), each filled by how much of it is planned, plus the end time. No grid: tapping a row lists its blocks.

enum class FillKind { WORK, EXTENDED, FIXED, EMPTY }

data class SegmentFill(val fraction: Float, val kind: FillKind)

data class WeekBlockLine(val key: String, val time: String, val title: String, val detail: String, val tone: BlockTone, val isFixed: Boolean)

data class WeekRow(
    val date: LocalDate,
    /** "Mon". */
    val label: String,
    /** "11:49", "~11:48" (estimated), "Jumu'ah" (no Dhuhr) or "today". */
    val sub: String,
    val isToday: Boolean,
    val segments: List<SegmentFill>,
    /** "19:15", or "–" when nothing is planned. */
    val endLabel: String,
    /** Ends after the normal end (shown in amber). */
    val late: Boolean,
    /** "Extended day", "Uses rest time", "2 not placed". */
    val flags: List<String>,
    val blocks: List<WeekBlockLine>,
    /** Spoken summary for the row, e.g. "Monday, ends 19:15, extended day, 4 blocks". */
    val description: String,
)

data class WeekRhythmUi(
    val start: LocalDate,
    /** "2 – 8 November". */
    val rangeLabel: String,
    val rows: List<WeekRow>,
    /** "9 – 15 Nov". */
    val nextWeekLabel: String,
    val nextWeekStart: LocalDate,
    val hasEstimatedTimes: Boolean,
)

object WeekRhythm {
    val segmentLabels = listOf("→Dhuhr", "→Asr", "→Mgh", "→Isha", "Isha→")
    private val boundaryPrayers = listOf(Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)
    private val defaults = mapOf(
        Prayer.DHUHR to LocalTime.of(12, 0), Prayer.ASR to LocalTime.of(14, 0),
        Prayer.MAGHRIB to LocalTime.of(16, 30), Prayer.ISHA to LocalTime.of(18, 0),
    )

    /**
     * Stretch boundaries for [date]: wake, Dhuhr, Asr, Maghrib, Isha, hard cap. A missing prayer (no Friday Dhuhr)
     * borrows the same prayer from another day of the week, so the columns still line up.
     */
    fun boundaries(date: LocalDate, anchors: List<PrayerAnchor>, settings: Settings): List<LocalDateTime> {
        val mids = boundaryPrayers.map { p ->
            val time = anchors.firstOrNull { it.date == date && it.prayer == p }?.begin
                ?: anchors.filter { it.prayer == p }.minByOrNull { Math.abs(it.date.toEpochDay() - date.toEpochDay()) }?.begin
                ?: defaults.getValue(p)
            date.atTime(time)
        }
        val raw = listOf(date.atTime(settings.wakeTime)) + mids + date.atTime(settings.hardCap)
        // Keep them in order even with odd settings (a wake time after Dhuhr).
        return raw.runningReduce { prev, t -> if (t.isBefore(prev)) prev else t }
    }

    private fun overlap(aStart: LocalDateTime, aEnd: LocalDateTime, bStart: LocalDateTime, bEnd: LocalDateTime): Int {
        val s = if (aStart.isAfter(bStart)) aStart else bStart
        val e = if (aEnd.isBefore(bEnd)) aEnd else bEnd
        return if (e.isAfter(s)) Fmt.minutesBetween(s, e) else 0
    }

    fun segments(day: WeekDay, anchors: List<PrayerAnchor>, settings: Settings): List<SegmentFill> {
        val b = boundaries(day.date, anchors, settings)
        return (0 until 5).map { i ->
            val from = b[i]
            val to = b[i + 1]
            val length = Fmt.minutesBetween(from, to)
            if (length == 0) return@map SegmentFill(0f, FillKind.EMPTY)
            var work = 0
            var extended = 0
            var fixed = 0
            // Minutes covered by any block (overlaps counted once), sampled per minute; a day is at most 1440.
            val covered = BooleanArray(length)
            day.blocks.forEach { blk ->
                val m = overlap(blk.start, blk.end, from, to)
                if (m == 0) return@forEach
                when {
                    blk.isFixed -> fixed += m
                    blk.tier >= Tier.EXTENDED -> extended += m
                    else -> work += m
                }
                val s = Fmt.minutesBetween(from, if (blk.start.isAfter(from)) blk.start else from)
                for (k in s until (s + m).coerceAtMost(length)) covered[k] = true
            }
            val fraction = covered.count { it }.toFloat() / length
            val kind = when {
                fraction == 0f -> FillKind.EMPTY
                extended > 0 -> FillKind.EXTENDED
                fixed > work -> FillKind.FIXED
                else -> FillKind.WORK
            }
            SegmentFill(fraction.coerceIn(0f, 1f), kind)
        }
    }

    fun flagLabels(day: WeekDay): List<String> = buildList {
        when {
            DayFlag.OVER_SOFT_LIMIT in day.flags -> add("Over the soft limit")
            DayFlag.EXTENDED_DAY in day.flags -> add("Extended day")
            DayFlag.USES_REST_TIME in day.flags -> add("Uses rest time")
        }
        if (day.overflowCount > 0) add("${day.overflowCount} not placed")
        if (DayFlag.HAS_OVERFLOW in day.flags && day.overflowCount == 0) add("Some work did not fit")
    }

    private fun line(b: TodayBlock) = WeekBlockLine(
        key = DayTimelines.blockKey(b),
        time = Fmt.range(b.start, b.end),
        title = DayTimelines.blockTitle(b),
        detail = DayTimelines.detail(b),
        tone = DayTimelines.tone(b.tier),
        isFixed = b.isFixed,
    )

    fun build(week: WeekState, anchors: List<PrayerAnchor>, settings: Settings, today: LocalDate): WeekRhythmUi {
        val rows = week.days.map { day ->
            val dhuhr = anchors.firstOrNull { it.date == day.date && it.prayer == Prayer.DHUHR }
            val interpolated = anchors.any { it.date == day.date && it.interpolated }
            val sub = when {
                day.date == today -> "today"
                dhuhr == null && day.date.dayOfWeek == DayOfWeek.FRIDAY -> "Jumu’ah"
                dhuhr == null -> ""
                else -> (if (dhuhr.interpolated) "~" else "") + Fmt.time(dhuhr.begin)
            }
            val end = day.endTime
            val late = end != null && end.isAfter(settings.normalEnd)
            val flags = flagLabels(day)
            val tasks = day.blocks.count { !it.isFixed }
            val spoken = buildList {
                add(day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK))
                add(if (end != null) "ends ${Fmt.time(end)}" else "nothing planned")
                addAll(flags.map { it.lowercase() })
                if (tasks > 0) add("$tasks ${if (tasks == 1) "block" else "blocks"}")
                if (interpolated) add("prayer times estimated")
            }.joinToString(", ")
            WeekRow(
                date = day.date,
                label = Fmt.weekday(day.date),
                sub = sub,
                isToday = day.date == today,
                segments = segments(day, anchors, settings),
                endLabel = end?.let(Fmt::time) ?: "–",
                late = late,
                flags = flags,
                blocks = day.blocks.sortedBy { it.start }.map(::line),
                description = spoken,
            )
        }
        val endInclusive = week.start.plusDays(6)
        val next = week.start.plusDays(7)
        return WeekRhythmUi(
            start = week.start,
            rangeLabel = Fmt.weekRange(week.start, endInclusive),
            rows = rows,
            nextWeekLabel = Fmt.weekRangeShort(next, next.plusDays(6)),
            nextWeekStart = next,
            hasEstimatedTimes = anchors.any { it.interpolated },
        )
    }
}
