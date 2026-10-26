package app.planner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import app.planner.calendar.CalendarEvent
import app.planner.calendar.EventTiming
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** FixedEventCache from the plan: every synced event; all-day ones hold ISO dates, timed ones ISO instants. */
@Entity(tableName = "fixed_event_cache")
data class CalendarEventEntity(
    @PrimaryKey val key: String,
    val calendarId: String,
    val eventId: String,
    val title: String,
    val allDay: Boolean,
    val start: String,
    val end: String,
)

fun CalendarEvent.toEntity(): CalendarEventEntity {
    val (allDay, start, end) = when (val t = timing) {
        is EventTiming.Timed -> Triple(false, t.start.toString(), t.end.toString())
        is EventTiming.AllDay -> Triple(true, t.start.toString(), t.endExclusive.toString())
    }
    return CalendarEventEntity("$calendarId|$id", calendarId, id, title, allDay, start, end)
}

fun CalendarEventEntity.toEvent() = CalendarEvent(
    id = eventId,
    calendarId = calendarId,
    title = title,
    timing = if (allDay) EventTiming.AllDay(LocalDate.parse(start), LocalDate.parse(end))
    else EventTiming.Timed(Instant.parse(start), Instant.parse(end)),
)

@Dao
interface CalendarEventDao {
    @Query("SELECT * FROM fixed_event_cache")
    fun observeAll(): Flow<List<CalendarEventEntity>>

    @Query("DELETE FROM fixed_event_cache")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<CalendarEventEntity>)
}
