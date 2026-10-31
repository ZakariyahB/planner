package app.planner.gcal.plan

import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Placement
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.parse.PrayerImport
import app.planner.engine.schedule.PrayerModel
import app.planner.gcal.CalendarDirectory
import app.planner.gcal.CalendarMapping
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Everything known about a stretch of days, read from the synced snapshots, the task store and settings:
 * fixed events, the app's own blocks, all-day template events and prayer anchors. Read-only.
 */
class PlanningContext(
    val directory: CalendarDirectory,
    val events: List<EventSnapshot>,
    val tasks: List<Task>,
    val settings: Settings,
    private val prayers: PrayerModel,
) {
    private val byRef = events.associateBy { it.ref }
    private val tasksById = tasks.associateBy { it.id }
    private val slot = settings.prayerSlotMin.toLong()

    fun snapshot(ref: EventRef): EventSnapshot? = byRef[ref]

    fun task(id: String?): Task? = id?.let(tasksById::get)

    fun isPrayer(e: EventSnapshot): Boolean = directory.isPrayer(e.ref.calendarId) || CalendarMapping.isPrayerTimes(e.calendarName)

    /** Writable calendar for a category, or null if the user has no such calendar or does not allow writing to it. */
    fun calendarFor(category: CalendarCategory): String? = directory.writableIdFor(category)

    fun isWritable(e: EventSnapshot): Boolean = !isPrayer(e) && directory.isWritable(e.ref.calendarId)

    private fun timed(e: EventSnapshot): EventTime.Timed? = e.time as? EventTime.Timed

    private fun touches(e: EventSnapshot, day: LocalDate): Boolean {
        val t = timed(e) ?: return false
        return BlockMath.overlaps(t.start, t.end, day.atStartOfDay(), day.plusDays(1).atStartOfDay())
    }

    /** Timed events that are not the app's own blocks and not prayer entries: the engine plans around them. */
    fun fixedOn(day: LocalDate): List<FixedEvent> =
        events.filter { !isPrayer(it) && !it.isPlannerBlock && touches(it, day) }.map { it.toFixed() }

    fun fixedIn(days: Collection<LocalDate>): List<FixedEvent> = days.flatMap(::fixedOn).distinctBy { it.ref }

    /** The app's own timed blocks starting on [day], earliest first. */
    fun blocksOn(day: LocalDate): List<EventSnapshot> =
        events.filter { e -> !isPrayer(e) && e.isPlannerBlock && timed(e)?.start?.toLocalDate() == day }
            .sortedBy { timed(it)!!.start }

    /** All-day events in category calendars: the weekly template. */
    fun templateEvents(from: LocalDate, toExclusive: LocalDate): List<EventSnapshot> = events.filter { e ->
        val t = e.time as? EventTime.AllDay ?: return@filter false
        !isPrayer(e) && directory.get(e.ref.calendarId)?.category != null && !t.start.isBefore(from) && t.start.isBefore(toExclusive)
    }

    private val imported: List<PrayerAnchor> by lazy {
        events.filter(::isPrayer).mapNotNull { e ->
            when (val t = e.time) {
                is EventTime.Timed -> PrayerImport.anchor(e.title, e.description, t.start.toLocalDate(), t.start.toLocalTime())
                is EventTime.AllDay -> PrayerImport.anchor(e.title, e.description, t.start, null)
            }
        }.distinctBy { it.date to it.prayer }
    }
    private val resolvedByDay = HashMap<LocalDate, List<PrayerAnchor>>()

    fun importedAnchors(): List<PrayerAnchor> = imported

    /** Resolved anchors (offsets, weekend interpolation, no Friday Dhuhr), before absorption. */
    fun anchors(days: List<LocalDate>): List<PrayerAnchor> =
        days.flatMap { day -> resolvedByDay.getOrPut(day) { prayers.resolve(listOf(day), imported, settings) } }

    /** Anchors for [day] with those inside fixed events removed: the ones that cost a slot. */
    fun effectiveAnchors(day: LocalDate): List<PrayerAnchor> = prayers.absorb(anchors(listOf(day)), fixedOn(day))

    fun maghrib(day: LocalDate): java.time.LocalTime? = anchors(listOf(day)).firstOrNull { it.prayer == Prayer.MAGHRIB }?.begin

    /** A block as a placement to plan around (or to keep exactly where it is). */
    fun asPlacement(e: EventSnapshot, taskId: String = "keep:${e.ref.eventId}"): Placement {
        val t = timed(e) ?: error("Not a timed event: ${e.ref}")
        val day = t.start.toLocalDate()
        val anchors = effectiveAnchors(day)
        val covered = BlockMath.covered(t.start, t.end, anchors)
        return Placement(
            taskId = taskId, start = t.start, end = t.end, prayerBufferMin = covered.size * slot.toInt(),
            coveredPrayers = covered.map { it.prayer }, tier = Tiers.of(t.start, t.end, maghrib(day), settings),
            interpolatedPrayer = covered.any { it.interpolated },
        )
    }

    /** Work minutes in an existing block: its length less the prayer slots it covers. */
    fun workMinutes(e: EventSnapshot): Int {
        val t = timed(e) ?: return 0
        return BlockMath.workMinutes(t.start, t.end, effectiveAnchors(t.start.toLocalDate()), settings.prayerSlotMin)
    }

    fun start(e: EventSnapshot): LocalDateTime? = timed(e)?.start

    private fun EventSnapshot.toFixed(): FixedEvent {
        val t = time as EventTime.Timed
        return FixedEvent(ref, title, t.start, t.end, organiserIsExternal = organiserIsExternal)
    }
}
