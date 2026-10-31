package app.planner.gcal

import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.fakes.InMemoryChangeSetStore
import app.planner.engine.fakes.InMemoryExecutionLogStore
import app.planner.engine.fakes.InMemorySettingsStore
import app.planner.engine.fakes.InMemorySnapshotStore
import app.planner.engine.fakes.InMemoryTaskStore
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.Prayer
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.parse.Durations
import app.planner.engine.ports.AuthState
import app.planner.engine.ports.CalendarInfo
import app.planner.engine.ports.CheckInRescheduler
import app.planner.gcal.fakes.FakeCalendarGateway
import app.planner.gcal.interim.InterimBlockPlacer
import app.planner.gcal.interim.InterimPlanningEngine
import app.planner.gcal.interim.InterimPrayerModel
import app.planner.gcal.sync.CalendarSync
import app.planner.gcal.write.DefaultCalendarWriter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Calendars, events and services for the reference week (Mon 2 to Sun 8 November 2026), over in-memory fakes. */
object Fixture {
    val monday: LocalDate = ReferenceWeek.monday
    fun day(i: Int): LocalDate = ReferenceWeek.days[i]
    fun at(i: Int, time: String): LocalDateTime = day(i).atTime(LocalTime.parse(time))

    const val PRAYER = ReferenceWeek.PRAYER_CALENDAR_ID
    const val HOLIDAYS = "cal-holidays"
    fun cal(c: CalendarCategory) = ReferenceWeek.calendarId(c)

    /** Academic keeps its real trailing space, to prove names are matched trimmed. */
    fun calendars(settings: Settings = Settings()): List<CalendarInfo> =
        CalendarCategory.entries.map { c ->
            CalendarMapping.info(cal(c), if (c == CalendarCategory.ACADEMIC) "Academic " else c.calendarName, "owner", settings.writableCategories)
        } + CalendarMapping.info(PRAYER, "Prayer times", "reader", settings.writableCategories) +
            CalendarMapping.info(HOLIDAYS, "Holidays in United Kingdom", "reader", settings.writableCategories)

    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    /** Weekday prayer entries for the reference week and the week after (same times), as the mosque calendar has them. */
    fun prayerEvents(): List<EventSnapshot> =
        ReferenceWeek.importedAnchors.flatMap { a -> listOf(a, a.copy(date = a.date.plusDays(7)), a.copy(date = a.date.minusDays(7))) }.map { a ->
            val start = a.date.atTime(a.begin)
            snapshot(
                PRAYER, "prayer-${a.prayer.name.lowercase()}-${a.date}", a.prayer.name.lowercase().replaceFirstChar { it.uppercase() },
                EventTime.Timed(start, start.plusMinutes(10)), description = "Begins ${a.begin.format(hm)}", calendarName = "Prayer times",
            )
        }

    /** The fixed events actually on the calendar (Halaqa and Jumu'ah prep are only assumed). */
    fun fixedEvents(): List<EventSnapshot> = ReferenceWeek.fixed.filter { !it.assumed }.map { f ->
        snapshot(f.ref.calendarId, f.ref.eventId, f.title, EventTime.Timed(f.start, f.end), organiserIsExternal = f.organiserIsExternal)
    }

    /** The weekly template as numbered all-day events, e.g. "2. Lecture - P&S (1h, from 14:00)". */
    fun templateEvents(tasks: List<Task> = ReferenceWeek.tasks): List<EventSnapshot> =
        tasks.groupBy { it.assignedDay }.flatMap { (_, dayTasks) ->
            dayTasks.mapIndexed { i, t ->
                val notes = listOfNotNull(
                    Durations.format(t.durationMin),
                    t.earliestStart?.let { "from ${it.format(hm)}" },
                    t.afterPrayer?.let { "after ${it.name.lowercase().replaceFirstChar { c -> c.uppercase() }}" },
                )
                snapshot(
                    cal(t.category), "allday-${t.id}", "${i + 1}. ${t.title} (${notes.joinToString(", ")})",
                    EventTime.AllDay(t.assignedDay!!, t.assignedDay!!.plusDays(1)),
                )
            }
        }

    fun snapshot(
        calendarId: String, id: String, title: String, time: EventTime, description: String? = null,
        organiserIsExternal: Boolean = false, recurring: String? = null, props: Map<String, String> = emptyMap(),
        calendarName: String? = null,
    ) = EventSnapshot(
        ref = EventRef(calendarId, id),
        calendarName = calendarName ?: calendars().firstOrNull { it.id == calendarId }?.name ?: calendarId,
        title = title, time = time, description = description, etag = "\"0\"", updated = Instant.EPOCH,
        recurringEventId = recurring, organiserIsExternal = organiserIsExternal, privateProps = props,
    )

    fun allEvents(): List<EventSnapshot> = prayerEvents() + fixedEvents() + templateEvents()

    fun prayer(name: String): Prayer = Prayer.valueOf(name.uppercase())
}

/**
 * Everything wired together over fakes: Google (a fake gateway), the stores, the interim engine, sync, the planner
 * service and the writer. [now] is mutable; the default is Sunday evening before the reference week.
 */
class TestWorld(
    var now: LocalDateTime = LocalDateTime.of(2026, 11, 1, 20, 0),
    events: List<EventSnapshot> = Fixture.allEvents(),
    val settings: Settings = Settings(),
    gatewayFactory: (List<CalendarInfo>, List<EventSnapshot>, () -> Instant) -> FakeCalendarGateway = ::IncrementalFakeGateway,
) {
    val clock: () -> Instant = { now.atZone(settings.zone).toInstant() }
    val gateway: FakeCalendarGateway = gatewayFactory(Fixture.calendars(settings), events, clock)
    val tasks = InMemoryTaskStore()
    val changeSets = InMemoryChangeSetStore()
    val snapshots = InMemorySnapshotStore()
    val settingsStore = InMemorySettingsStore(settings)
    val logs = InMemoryExecutionLogStore()
    val auth = AuthTracker(AuthState.SIGNED_IN)
    var reschedules = 0
    val rescheduler = CheckInRescheduler { reschedules++ }
    val sync = CalendarSync(gateway, snapshots, auth, rescheduler, { now }, clock)
    val service = PlannerService(
        engine = InterimPlanningEngine(), prayers = InterimPrayerModel(), placer = InterimBlockPlacer(), sync = sync,
        tasks = tasks, changeSets = changeSets, snapshots = snapshots, settings = settingsStore, logs = logs, auth = auth,
        now = { now }, clock = clock, ids = IdSource.counting("p"), fetch = { runCatching { gateway.event(it) }.getOrNull() },
    )
    val writer = DefaultCalendarWriter(
        gateway, changeSets, snapshots, tasks, rescheduler, auth, clock, IdSource.counting("w"), afterWrite = { sync.refresh() },
    )

    /** Sync and import the reference week's template, as the app does on first open. */
    suspend fun start(): TestWorld {
        sync.refresh()
        service.importTemplate(Fixture.monday, Fixture.monday.plusDays(7))
        return this
    }

    suspend fun event(ref: EventRef): EventSnapshot? = gateway.event(ref)

    suspend fun eventsOn(day: LocalDate): List<EventSnapshot> = gateway.events(day, day.plusDays(1))
}

/** A fake whose incremental reads report only what changed since the token, including deletions, like Google. */
open class IncrementalFakeGateway(
    calendars: List<CalendarInfo>,
    initial: List<EventSnapshot>,
    clock: () -> Instant,
) : FakeCalendarGateway(calendars, initial, clock) {
    private var seq = 0
    private val changed = mutableListOf<Triple<Int, EventRef, Boolean>>() // seq, ref, deleted
    val syncCalls = mutableListOf<Pair<String, String?>>()
    var tokenGone = false

    private fun log(ref: EventRef, deleted: Boolean) { changed += Triple(++seq, ref, deleted) }

    override suspend fun insert(calendarId: String, eventId: String, draft: EventDraft): EventSnapshot {
        val existed = events.containsKey(EventRef(calendarId, eventId))
        return super.insert(calendarId, eventId, draft).also { if (!existed) log(it.ref, false) }
    }

    override suspend fun patch(ref: EventRef, draft: EventDraft, scope: RecurrenceScope): EventSnapshot =
        super.patch(ref, draft, scope).also { if (it.ref != ref) log(ref, true); log(it.ref, false) }

    override suspend fun delete(ref: EventRef, scope: RecurrenceScope) {
        super.delete(ref, scope); log(ref, true)
    }

    override suspend fun changesSince(calendarId: String, syncToken: String?): app.planner.gcal.IncrementalPage {
        if (authExpired) throw CalendarException.AuthExpired()
        syncCalls += calendarId to syncToken
        if (syncToken == null) {
            return IncrementalPage(events.values.filter { it.ref.calendarId == calendarId }, emptyList(), "t$seq")
        }
        if (tokenGone) throw CalendarException.SyncTokenGone(calendarId)
        val since = syncToken.removePrefix("t").toInt()
        val recent = changed.filter { it.first > since && it.second.calendarId == calendarId }
        val latest = recent.groupBy { it.second }.mapValues { it.value.last() }
        return IncrementalPage(
            changed = latest.filter { !it.value.third }.keys.mapNotNull { events[it] },
            deletedRefs = latest.filter { it.value.third }.keys.toList(),
            nextSyncToken = "t$seq",
        )
    }

    /** The user drags or edits an event in Google Calendar. */
    fun userEdit(ref: EventRef, transform: (EventSnapshot) -> EventSnapshot) {
        externalEdit(ref, transform); log(ref, false)
    }

    fun userDelete(ref: EventRef) {
        events.remove(ref); log(ref, true)
    }
}

/** Applies the wrong change on purpose: patches land 15 minutes late and inserts get the wrong title. */
class MisbehavingGateway(
    calendars: List<CalendarInfo>,
    initial: List<EventSnapshot>,
    clock: () -> Instant,
) : IncrementalFakeGateway(calendars, initial, clock) {
    var misbehave = true

    override suspend fun patch(ref: EventRef, draft: EventDraft, scope: RecurrenceScope): EventSnapshot {
        if (!misbehave) return super.patch(ref, draft, scope)
        val wrong = when (val t = draft.time) {
            is EventTime.Timed -> draft.copy(time = EventTime.Timed(t.start.plusMinutes(15), t.end.plusMinutes(15)))
            is EventTime.AllDay -> draft.copy(title = draft.title + " (oops)")
        }
        super.patch(ref, wrong, scope)
        return snapshot(EventRef(draft.calendarId, ref.eventId), draft) // and it claims the right thing happened
    }

    override suspend fun insert(calendarId: String, eventId: String, draft: EventDraft): EventSnapshot {
        if (!misbehave) return super.insert(calendarId, eventId, draft)
        super.insert(calendarId, eventId, draft.copy(title = draft.title.uppercase() + "!"))
        return snapshot(EventRef(calendarId, eventId), draft)
    }
}

/** Models recurring series: instances share recurringEventId; whole-series operations touch every instance. */
class RecurringFakeGateway(
    calendars: List<CalendarInfo>,
    initial: List<EventSnapshot>,
    clock: () -> Instant,
) : IncrementalFakeGateway(calendars, initial, clock) {
    override suspend fun delete(ref: EventRef, scope: RecurrenceScope) {
        val series = events[ref]?.recurringEventId
        if (scope == RecurrenceScope.WHOLE_SERIES && series != null) {
            events.values.filter { it.recurringEventId == series && it.ref != ref }.forEach { super.delete(it.ref, scope) }
        }
        super.delete(ref, scope)
    }
}
