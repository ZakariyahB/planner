package app.planner.engine.codec

import app.planner.engine.changeset.EventTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The indexed columns a database keeps beside each JSON record, derived here (pure, tested) so the Room stores match
 * the in-memory fakes exactly. Wall-clock values are keyed as if they were UTC: the key orders local times, it is not
 * an instant.
 */
object StoreKeys {
    private const val NANOS = 1_000_000_000L

    /** Sortable key for a local wall-clock time: seconds since 1970-01-01T00:00 times 1e9 plus nanos (good to 2262). */
    fun wallNanos(t: LocalDateTime): Long = t.toEpochSecond(ZoneOffset.UTC) * NANOS + t.nano

    /** Sortable key for an instant (same range). */
    fun instantNanos(t: Instant): Long = t.epochSecond * NANOS + t.nano

    /**
     * The dates an event covers, as epoch days [first, endExclusive). A query for [from, toExclusive) matches when
     * first < toExclusive and endExclusive > from. Same rule as InMemorySnapshotStore: a timed event covers its start
     * date through its end date inclusive; an all-day event covers [start, endExclusive).
     */
    fun eventDays(time: EventTime): DayRange = when (time) {
        is EventTime.Timed -> DayRange(time.start.toLocalDate().toEpochDay(), time.end.toLocalDate().plusDays(1).toEpochDay())
        is EventTime.AllDay -> DayRange(time.start.toEpochDay(), time.endExclusive.toEpochDay())
    }

    /** Order of events within a query result: by start (an all-day event sorts at the start of its first day). */
    fun eventStartKey(time: EventTime): Long = when (time) {
        is EventTime.Timed -> wallNanos(time.start)
        is EventTime.AllDay -> wallNanos(time.start.atStartOfDay())
    }

    /** The query a database runs: firstDay < toExclusive AND endDayExclusive > from. */
    fun overlaps(days: DayRange, from: LocalDate, toExclusive: LocalDate): Boolean =
        days.firstDay < toExclusive.toEpochDay() && days.endDayExclusive > from.toEpochDay()

    data class DayRange(val firstDay: Long, val endDayExclusive: Long)
}
