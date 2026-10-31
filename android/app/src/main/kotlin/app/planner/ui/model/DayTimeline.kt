package app.planner.ui.model

import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Tier
import app.planner.engine.ports.TodayBlock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Today, laid out as stretches between the prayers (the Rhythm direction): Before Dhuhr, Dhuhr, a stretch, Asr, ...
// Isha, After Isha. A block belongs to the stretch holding its midpoint. The block happening now becomes the big card.

enum class BlockTone {
    NORMAL,

    /** Tier 1: the rest zone after Maghrib. */
    REST,

    /** Tier 2 or 3: shown in amber. */
    EXTENDED,
}

data class TimelineBlock(
    val block: TodayBlock,
    /** Stable key for lists and for the execution log. */
    val key: String,
    /** "P&S CW (2/2)". */
    val title: String,
    val startLabel: String,
    val rangeLabel: String,
    /** "Lectures · pauses for Asr · Extended day". */
    val detail: String,
    /** For the now card: "3h of work, with 15 minutes for Dhuhr inside it". */
    val workLabel: String,
    val tone: BlockTone,
    val isNow: Boolean,
    val isPast: Boolean,
    val outcome: ExecutionOutcome,
    val log: ExecutionLog?,
) {
    val isDone: Boolean get() = outcome == ExecutionOutcome.DONE || outcome == ExecutionOutcome.STOPPED
}

data class Stretch(
    val id: String,
    /** "Before Dhuhr", "After Asr". Used as the section's accessibility label. */
    val title: String,
    val blocks: List<TimelineBlock>,
    /** "PintOS until 12:45, then free till 14:00", or "Free until Asr". */
    val note: String?,
    /** Contains time past the normal end: the line is drawn dashed in amber. */
    val extended: Boolean,
    /** Contains now: the line is drawn in the accent colour. */
    val current: Boolean,
)

data class PrayerMarker(
    val prayer: Prayer,
    /** "11:49", or "~11:48" when interpolated. */
    val timeLabel: String,
    /** "rest until 18:00" for Maghrib. */
    val note: String?,
    val interpolated: Boolean,
    /** The next prayer from now: drawn with the accent ring. */
    val next: Boolean,
)

sealed interface TimelineItem {
    val key: String

    data class Marker(val marker: PrayerMarker) : TimelineItem {
        override val key: String get() = "marker-${marker.prayer}"
    }

    data class Section(val stretch: Stretch) : TimelineItem {
        override val key: String get() = "stretch-${stretch.id}"
    }
}

data class DayTimeline(
    val date: LocalDate,
    val dateLabel: String,
    /** The stretch we are in now, e.g. "Before Dhuhr". */
    val heading: String,
    /** "1h 09m to Dhuhr at 11:49 · PintOS carries on after". */
    val subheading: String,
    val items: List<TimelineItem>,
    val now: TimelineBlock?,
    val next: TimelineBlock?,
    val isEmpty: Boolean,
)

object DayTimelines {
    /** Prayers that bound the work-day stretches, in order. Fajr is never an anchor. */
    private val order = listOf(Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)

    fun blockKey(b: TodayBlock): String =
        (b.ref?.let { "${it.calendarId}/${it.eventId}" } ?: b.taskId ?: b.title) + "@" + b.start

    fun blockTitle(b: TodayBlock): String = listOfNotNull(b.title, b.chunkLabel).joinToString(" ")

    fun tone(tier: Tier): BlockTone = when (tier) {
        Tier.NORMAL -> BlockTone.NORMAL
        Tier.REST -> BlockTone.REST
        Tier.EXTENDED, Tier.OVER_SOFT_LIMIT -> BlockTone.EXTENDED
    }

    fun tierLabel(tier: Tier): String? = when (tier) {
        Tier.NORMAL -> null
        Tier.REST -> "Rest zone"
        Tier.EXTENDED -> "Extended day"
        Tier.OVER_SOFT_LIMIT -> "Over the soft limit"
    }

    private fun outcomeLabel(log: ExecutionLog?): String? = when (log?.outcome) {
        null, ExecutionOutcome.PENDING -> null
        ExecutionOutcome.STARTED -> log.actualStart?.let { "started ${Fmt.time(it)}" } ?: "started"
        ExecutionOutcome.DONE -> "done"
        ExecutionOutcome.EXTENDED -> "asked for more time"
        ExecutionOutcome.STOPPED -> "stopped early"
        ExecutionOutcome.SKIPPED -> "skipped"
    }

    fun detail(b: TodayBlock, log: ExecutionLog? = b.execution): String {
        val parts = mutableListOf<String>()
        parts += if (b.isFixed) "${b.calendarName} · fixed" else b.calendarName
        if (b.coveredPrayers.isNotEmpty()) parts += "pauses for ${Fmt.prayers(b.coveredPrayers)}"
        tierLabel(b.tier)?.let { parts += it }
        outcomeLabel(log)?.let { parts += it }
        return parts.joinToString(" · ")
    }

    fun workLabel(b: TodayBlock): String {
        if (b.isFixed) return "Fixed · until ${Fmt.time(b.end)}"
        val total = Fmt.minutesBetween(b.start, b.end)
        val work = (total - b.prayerBufferMin).coerceAtLeast(0)
        val base = "${Fmt.duration(work)} of work"
        return if (b.coveredPrayers.isEmpty() || b.prayerBufferMin == 0) base
        else "$base, with ${b.prayerBufferMin} minutes for ${Fmt.prayers(b.coveredPrayers)} inside it"
    }

    /** The block happening now. A task block wins over a fixed event it overlaps. */
    fun current(blocks: List<TodayBlock>, now: LocalDateTime): TodayBlock? {
        val live = blocks.filter { !now.isBefore(it.start) && now.isBefore(it.end) }
        return live.firstOrNull { !it.isFixed } ?: live.firstOrNull()
    }

    fun build(
        date: LocalDate,
        blocks: List<TodayBlock>,
        anchors: List<PrayerAnchor>,
        now: LocalDateTime,
        normalEnd: LocalTime,
        slotMin: Int = 15,
        logs: Map<String, ExecutionLog> = emptyMap(),
    ): DayTimeline {
        val sorted = blocks.sortedWith(compareBy<TodayBlock> { it.start }.thenBy { it.end })
        val dayAnchors = anchors.filter { it.date == date && it.prayer in order }
            .sortedBy { it.begin }
            .distinctBy { it.prayer }
        val currentBlock = if (now.toLocalDate() == date) current(sorted, now) else null

        fun toUi(b: TodayBlock): TimelineBlock {
            val key = blockKey(b)
            val log = logs[key] ?: b.execution
            return TimelineBlock(
                block = b,
                key = key,
                title = blockTitle(b),
                startLabel = Fmt.time(b.start),
                rangeLabel = Fmt.range(b.start, b.end),
                detail = detail(b, log),
                workLabel = workLabel(b),
                tone = tone(b.tier),
                isNow = b === currentBlock,
                isPast = !now.isBefore(b.end),
                outcome = log?.outcome ?: ExecutionOutcome.PENDING,
                log = log,
            )
        }

        val ui = sorted.map(::toUi)
        val bounds: List<LocalDateTime> = dayAnchors.map { date.atTime(it.begin) }
        // Stretch i covers [bounds[i-1], bounds[i]); stretch 0 is before the first anchor, the last is after the last.
        fun stretchIndex(t: LocalDateTime): Int = bounds.count { !t.isBefore(it) }
        // A block sits in the stretch holding most of it (its midpoint): Lecture - SDE at 14:00 with Asr at 14:04
        // reads as an after-Asr block, as on the mock.
        fun home(b: TimelineBlock): Int =
            stretchIndex(b.block.start.plusMinutes(Fmt.minutesBetween(b.block.start, b.block.end) / 2L))
        val nowStretch = if (now.toLocalDate() == date) stretchIndex(now) else -1
        val normalEndAt = date.atTime(normalEnd)

        fun stretchTitle(i: Int): String = when {
            dayAnchors.isEmpty() -> "Today"
            i == 0 -> "Before ${Fmt.prayer(dayAnchors[0].prayer)}"
            else -> "After ${Fmt.prayer(dayAnchors[i - 1].prayer)}"
        }

        val items = mutableListOf<TimelineItem>()
        val nextAnchorIndex = if (nowStretch >= 0) nowStretch.takeIf { it < dayAnchors.size } else null
        for (i in 0..dayAnchors.size) {
            val from = if (i == 0) null else bounds[i - 1]
            val to = bounds.getOrNull(i)
            val inside = ui.filter { home(it) == i }
            val carried = if (from == null) null else ui.lastOrNull { home(it) < i && it.block.end.isAfter(from) }
            val isCurrent = i == nowStretch
            val note = when {
                inside.isNotEmpty() -> null
                carried != null -> {
                    val until = "${carried.title} until ${Fmt.time(carried.block.end)}"
                    val nextStart = ui.firstOrNull { !it.block.start.isBefore(carried.block.end) }?.block?.start
                    val freeTill = listOfNotNull(nextStart, to).minOrNull()
                    when {
                        freeTill == null -> "$until, then free"
                        !carried.block.end.isBefore(freeTill) -> until
                        else -> "$until, then free till ${Fmt.time(freeTill)}"
                    }
                }
                isCurrent && to != null -> "Free until ${Fmt.prayer(dayAnchors[i].prayer)}"
                isCurrent -> "Nothing else planned"
                else -> null
            }
            val extended = inside.any { it.tone == BlockTone.EXTENDED } ||
                (inside.isNotEmpty() && inside.any { it.block.end.isAfter(normalEndAt) })
            if (inside.isNotEmpty() || note != null) {
                items += TimelineItem.Section(Stretch("s$i", stretchTitle(i), inside, note, extended, isCurrent))
            }
            if (i < dayAnchors.size) {
                val a = dayAnchors[i]
                val slotEnd = a.begin.plusMinutes(slotMin.toLong())
                val markerNote = if (a.prayer == Prayer.MAGHRIB && slotEnd.isBefore(normalEnd)) "rest until ${Fmt.time(normalEnd)}" else null
                items += TimelineItem.Marker(
                    PrayerMarker(
                        prayer = a.prayer,
                        timeLabel = (if (a.interpolated) "~" else "") + Fmt.time(a.begin),
                        note = markerNote,
                        interpolated = a.interpolated,
                        next = nextAnchorIndex == i,
                    ),
                )
            }
        }

        val nowUi = ui.firstOrNull { it.isNow }
        val nextUi = ui.firstOrNull { it.block.start.isAfter(now) && !it.block.isFixed } ?: ui.firstOrNull { it.block.start.isAfter(now) }
        val heading = when {
            now.toLocalDate() != date -> Fmt.longDate(date)
            else -> stretchTitle(nowStretch.coerceIn(0, dayAnchors.size))
        }
        val subheading = buildSubheading(now, date, dayAnchors, nextAnchorIndex, nowUi, nextUi)
        return DayTimeline(
            date = date,
            dateLabel = Fmt.longDate(date),
            heading = heading,
            subheading = subheading,
            items = items,
            now = nowUi,
            next = nextUi,
            isEmpty = ui.isEmpty(),
        )
    }

    private fun buildSubheading(
        now: LocalDateTime,
        date: LocalDate,
        anchors: List<PrayerAnchor>,
        nextAnchorIndex: Int?,
        nowBlock: TimelineBlock?,
        nextBlock: TimelineBlock?,
    ): String {
        if (now.toLocalDate() != date) return nextBlock?.let { "First up: ${it.title} at ${it.startLabel}" } ?: "Nothing planned"
        val parts = mutableListOf<String>()
        val anchor = nextAnchorIndex?.let { anchors[it] }
        if (anchor != null) {
            val at = date.atTime(anchor.begin)
            parts += "${Fmt.duration(Fmt.minutesBetween(now, at))} to ${Fmt.prayer(anchor.prayer)} at ${Fmt.time(anchor.begin)}"
            if (nowBlock != null && nowBlock.block.end.isAfter(at)) parts += "${nowBlock.title} carries on after"
        } else if (nowBlock != null) {
            parts += "${nowBlock.title} until ${Fmt.time(nowBlock.block.end)}"
        }
        if (nowBlock == null) {
            parts += nextBlock?.let { "Next: ${it.title} at ${it.startLabel}" } ?: "Nothing else planned today"
        }
        return parts.joinToString(" · ")
    }
}
