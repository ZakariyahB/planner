package app.planner.engine.codec

import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.fakes.InMemorySnapshotStore
import app.planner.engine.model.EventRef
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class StoreKeysTest {
    private val day = LocalDate.of(2026, 10, 25) // the clock goes back in London that night: keys are wall-clock anyway

    @Test
    fun `wall-clock keys order like the times they come from`() {
        val times = listOf(
            day.atTime(0, 0), day.atTime(0, 0, 0, 1), day.atTime(1, 30), day.atTime(1, 30, 0, 999_999_999),
            day.atTime(23, 59, 59), day.plusDays(1).atStartOfDay(), LocalDateTime.of(1969, 12, 31, 23, 0),
        )
        assertEquals(times.sorted(), times.sortedBy(StoreKeys::wallNanos))
        assertEquals(times.size, times.map(StoreKeys::wallNanos).toSet().size)
    }

    @Test
    fun `instant keys order like the instants`() {
        val a = Instant.parse("2026-11-02T18:00:00Z")
        val list = listOf(a.plusNanos(1), a, a.plusSeconds(1), a.minusNanos(1))
        assertEquals(list.sorted(), list.sortedBy(StoreKeys::instantNanos))
    }

    @Test
    fun `an all-day event sorts at the start of its day`() {
        val allDay = EventTime.AllDay(day, day.plusDays(1))
        val timed = EventTime.Timed(day.atTime(0, 1), day.atTime(1, 0))
        assertTrue(StoreKeys.eventStartKey(allDay) < StoreKeys.eventStartKey(timed))
        assertEquals(StoreKeys.wallNanos(day.atStartOfDay()), StoreKeys.eventStartKey(allDay))
    }

    @Test
    fun `day keys select exactly what the in-memory store selects`() = runBlocking {
        // The Room query is "firstDay < to AND endDayExclusive > from" on these keys; it must agree with the fake.
        val rnd = Random(42)
        val events = (0 until 300).map { i ->
            val start = day.plusDays(rnd.nextLong(-5, 6))
            val time = if (rnd.nextBoolean()) {
                EventTime.AllDay(start, start.plusDays(rnd.nextLong(0, 4)))
            } else {
                val s = start.atTime(rnd.nextInt(0, 24), rnd.nextInt(0, 4) * 15)
                EventTime.Timed(s, s.plusMinutes(rnd.nextLong(0, 60 * 30)))
            }
            EventSnapshot(EventRef("cal", "e$i"), "Cal", "t$i", time, etag = "x", updated = Instant.EPOCH)
        }
        val fake = InMemorySnapshotStore(events)
        for (from in -6L..6L) for (len in 0L..4L) {
            val f = day.plusDays(from)
            val t = f.plusDays(len)
            val expected = fake.events(f, t).map { it.ref.eventId }.toSet()
            val actual = events.filter { StoreKeys.overlaps(StoreKeys.eventDays(it.time), f, t) }.map { it.ref.eventId }.toSet()
            assertEquals(expected, actual, "range $f..$t")
        }
    }
}
