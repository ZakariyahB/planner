package app.planner.engine.fakes

import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.CalendarCategory.ACADEMIC
import app.planner.engine.model.CalendarCategory.LECTURES
import app.planner.engine.model.CalendarCategory.PERSONAL
import app.planner.engine.model.CalendarCategory.QURAN_ARABIC
import app.planner.engine.model.EventRef
import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Placement
import app.planner.engine.model.Prayer
import app.planner.engine.model.Prayer.ASR
import app.planner.engine.model.Prayer.DHUHR
import app.planner.engine.model.Prayer.ISHA
import app.planner.engine.model.Prayer.MAGHRIB
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Task
import app.planner.engine.model.TaskKind
import app.planner.engine.model.Tier
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The reference week from newplan section 13 (Mon 2 to Sun 8 November 2026). Shared fixture for engine tests,
 * fakes and UI previews. Reference blocks are for comparison, not strict equality.
 */
object ReferenceWeek {
    val monday: LocalDate = LocalDate.of(2026, 11, 2)
    val days: List<LocalDate> = (0L..6L).map { monday.plusDays(it) }

    fun calendarId(category: CalendarCategory): String = "cal-" + category.name.lowercase()
    const val PRAYER_CALENDAR_ID = "cal-prayer-times"

    private fun t(s: String): LocalTime = LocalTime.parse(s)
    private fun at(day: Int, time: String): LocalDateTime = days[day].atTime(t(time))

    /** Dhuhr, Asr, Maghrib, Isha per day; Sat and Sun interpolated; no Friday Dhuhr. */
    private val anchorTable: List<List<String?>> = listOf(
        listOf("11:49", "14:05", "16:35", "18:03"),
        listOf("11:49", "14:04", "16:33", "18:02"),
        listOf("11:49", "14:02", "16:31", "18:00"),
        listOf("11:49", "14:01", "16:30", "18:00"),
        listOf(null, "14:00", "16:28", "17:58"),
        listOf("11:48", "13:59", "16:26", "17:57"),
        listOf("11:48", "13:57", "16:25", "17:56"),
    )

    val anchors: List<PrayerAnchor> = anchorTable.flatMapIndexed { i, row ->
        listOf(DHUHR, ASR, MAGHRIB, ISHA).zip(row).mapNotNull { (p, time) ->
            time?.let { PrayerAnchor(days[i], p, t(it), interpolated = i >= 5) }
        }
    }

    /** Weekday anchors only, as imported from the Prayer times calendar (before weekend interpolation). */
    val importedAnchors: List<PrayerAnchor> = anchors.filter { !it.interpolated }

    fun anchorsOn(date: LocalDate): List<PrayerAnchor> = anchors.filter { it.date == date }

    private fun fixed(id: String, cat: CalendarCategory, title: String, day: Int, from: String, to: String, external: Boolean = false, assumed: Boolean = false) =
        FixedEvent(EventRef(calendarId(cat), id), title, at(day, from), at(day, to), organiserIsExternal = external, assumed = assumed)

    val fixed: List<FixedEvent> = listOf(
        fixed("bol-wed", PERSONAL, "BoL", 2, "10:15", "12:45"),
        fixed("halaqa-wed", PERSONAL, "Halaqa", 2, "14:00", "16:30", assumed = true),
        fixed("quran-class-thu", PERSONAL, "Quran class (Sh Anass)", 3, "18:00", "18:30"),
        fixed("jumuah-prep-fri", CalendarCategory.SOCIETY, "Jumu'ah prep", 4, "12:00", "14:00", assumed = true),
        fixed("arabic-lesson-sun", QURAN_ARABIC, "Arabic lesson", 6, "12:15", "13:15", external = true),
    )

    private fun task(
        id: String, title: String, cat: CalendarCategory, min: Int, day: Int,
        from: String? = null, after: Prayer? = null, kind: TaskKind = TaskKind.DEEP, priority: Int = 0,
    ) = Task(
        id = id, title = title, category = cat, durationMin = min, assignedDay = days[day],
        earliestStart = from?.let(::t), afterPrayer = after, kind = kind, priority = priority,
        sourceEvent = EventRef(calendarId(cat), "allday-$id"),
    )

    private val lecture = TaskKind.PASSIVE

    /** The week's template tasks, one per day assignment (PintOS appears on three days). */
    val tasks: List<Task> = listOf(
        task("ps-sheet-mon", "P&S problem sheet", ACADEMIC, 120, 0),
        task("lec-cp2-mon", "Lecture - CP2", LECTURES, 60, 0, kind = lecture),
        task("lec-ps-mon", "Lecture - P&S", LECTURES, 60, 0, from = "14:00", kind = lecture),
        task("lec-ada-mon", "Lecture - ADA", LECTURES, 120, 0, from = "17:00", kind = lecture),
        task("pintos-tue", "PintOS", ACADEMIC, 180, 1),
        task("lec-sde-tue", "Lecture - SDE", LECTURES, 60, 1, from = "14:00", kind = lecture),
        task("arabic-hw-tue", "Arabic hw", QURAN_ARABIC, 60, 1, after = ASR),
        task("lec-os-tue", "Lecture - OS", LECTURES, 60, 1, from = "18:00", kind = lecture),
        task("ps-cw-wed", "P&S CW", ACADEMIC, 90, 2),
        task("os-ws-wed", "OS worksheet", ACADEMIC, 120, 2),
        task("sde-thu", "SDE", ACADEMIC, 180, 3),
        task("lec-os-thu", "Lecture - OS", LECTURES, 120, 3, from = "15:00", kind = lecture),
        task("tut-ada-thu", "Tutorial - ADA", LECTURES, 60, 3, from = "19:00", kind = lecture),
        task("ada-ws-fri", "ADA worksheet", ACADEMIC, 90, 4),
        task("pintos-fri", "PintOS", ACADEMIC, 120, 4),
        task("lec-ps-fri", "Lecture - P&S", LECTURES, 120, 4, from = "15:15", kind = lecture),
        task("tut-ps-sat", "Tutorial - P&S", LECTURES, 60, 5, kind = lecture),
        task("lec-sde-sat", "Lecture - SDE", LECTURES, 60, 5, kind = lecture),
        task("ps-cw-sat", "P&S CW", ACADEMIC, 120, 5),
        task("arabic-hw-sat", "Arabic hw", QURAN_ARABIC, 120, 5, after = ASR),
        task("pintos-sun", "PintOS", ACADEMIC, 180, 6),
        task("internship-sun", "Internship apps", PERSONAL, 120, 6),
        task("catchup-sun", "Catch-up + plan next week", PERSONAL, 120, 6, kind = TaskKind.OVERFLOW),
    )

    private fun p(
        id: String, day: Int, from: String, to: String, buffer: Int = 0, covered: List<Prayer> = emptyList(),
        chunk: Int = 1, of: Int = 1, tier: Tier = Tier.NORMAL,
    ) = Placement(id, chunk, of, at(day, from), at(day, to), buffer, covered, tier, interpolatedPrayer = day >= 5 && covered.isNotEmpty())

    /** newplan 13.3 reference blocks. */
    val referencePlacements: List<Placement> = listOf(
        p("ps-sheet-mon", 0, "09:30", "11:30"),
        p("lec-cp2-mon", 0, "11:30", "12:45", 15, listOf(DHUHR)),
        p("lec-ps-mon", 0, "14:00", "15:15", 15, listOf(ASR)),
        p("lec-ada-mon", 0, "17:00", "19:15", 15, listOf(ISHA), tier = Tier.EXTENDED),
        p("pintos-tue", 1, "09:30", "12:45", 15, listOf(DHUHR)),
        p("lec-sde-tue", 1, "14:00", "15:15", 15, listOf(ASR)),
        p("arabic-hw-tue", 1, "15:15", "16:15"),
        p("lec-os-tue", 1, "18:00", "19:15", 15, listOf(ISHA), tier = Tier.EXTENDED),
        p("ps-cw-wed", 2, "09:30", "10:15", chunk = 1, of = 2),
        p("os-ws-wed", 2, "12:45", "14:00", chunk = 1, of = 2),
        p("ps-cw-wed", 2, "16:46", "17:31", chunk = 2, of = 2, tier = Tier.REST),
        p("os-ws-wed", 2, "17:31", "18:31", 15, listOf(ISHA), chunk = 2, of = 2, tier = Tier.EXTENDED),
        p("sde-thu", 3, "09:30", "12:45", 15, listOf(DHUHR)),
        p("lec-os-thu", 3, "15:00", "17:15", 15, listOf(MAGHRIB), tier = Tier.REST),
        p("tut-ada-thu", 3, "19:00", "20:00", tier = Tier.EXTENDED),
        p("ada-ws-fri", 4, "09:30", "11:00"),
        p("pintos-fri", 4, "11:00", "12:00", chunk = 1, of = 2),
        p("pintos-fri", 4, "14:15", "15:15", chunk = 2, of = 2),
        p("lec-ps-fri", 4, "15:15", "17:30", 15, listOf(MAGHRIB), tier = Tier.REST),
        p("tut-ps-sat", 5, "09:30", "10:30"),
        p("lec-sde-sat", 5, "10:30", "11:30"),
        p("ps-cw-sat", 5, "11:30", "13:45", 15, listOf(DHUHR)),
        p("arabic-hw-sat", 5, "14:15", "16:15"),
        p("pintos-sun", 6, "09:30", "11:30", chunk = 1, of = 2),
        p("pintos-sun", 6, "13:15", "14:30", 15, listOf(ASR), chunk = 2, of = 2),
        p("internship-sun", 6, "14:30", "16:45", 15, listOf(MAGHRIB), tier = Tier.REST),
        p("catchup-sun", 6, "16:45", "19:00", 15, listOf(ISHA), tier = Tier.EXTENDED),
    )
}
