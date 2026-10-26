package app.planner.routines

import app.planner.calendar.FixedEvent
import app.planner.scheduler.DayRules
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The notifications of docs/PLAN.md "Routines and notifications" that this app owns, each with its own channel. */
enum class Routine(val timed: Boolean) {
    /** First sleep block ended near Fajr: readiness so far and how long to go back to bed. */
    PROVISIONAL_READINESS(false),

    /** Phone unlocked after the final wake: readiness and the day. */
    MORNING_BRIEF(false),
    WORK_STOPS(true),
    PLAN_READY(true),
    LIGHTS_OUT(true),
    SUNDAY_REVIEW(true);

    companion object {
        val TIMED: List<Routine> = entries.filter { it.timed }

        fun parse(name: String?): Routine? = entries.firstOrNull { it.name == name }
    }
}

/** When each timed routine fires next, in the user's own clock so daylight saving never shifts them. */
object RoutineSchedule {
    const val LIGHTS_OUT_WARNING_MIN = 30
    val SUNDAY_REVIEW_AT: LocalTime = LocalTime.of(19, 0)

    private const val LOOKAHEAD_DAYS = 8L

    /**
     * The first trigger strictly after [now]. [fixedMinutesOn] is the length of fixed events on a date, which
     * decides whether it is a packed day and so whether work stops at 21:00 or 22:00.
     */
    fun next(
        routine: Routine,
        now: Instant,
        zone: ZoneId,
        rules: DayRules = DayRules(),
        fixedMinutesOn: (LocalDate) -> Int = { 0 },
    ): Instant {
        require(routine.timed) { "$routine is not a timed routine" }
        val today = now.atZone(zone).toLocalDate()
        for (offset in 0..LOOKAHEAD_DAYS) {
            val date = today.plusDays(offset)
            val at = triggerOn(routine, date, zone, rules, fixedMinutesOn) ?: continue
            if (at > now) return at
        }
        error("no trigger for $routine within $LOOKAHEAD_DAYS days of $now")
    }

    /**
     * Minutes of fixed events on [date], clipped to that day, as the scheduler counts them when it decides
     * whether the day is packed (overlapping events are added, not merged).
     */
    fun fixedMinutesOn(date: LocalDate, zone: ZoneId, events: List<FixedEvent>): Int {
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        return events.sumOf { e ->
            val from = maxOf(e.start, dayStart)
            val to = minOf(e.end, dayEnd)
            if (to > from) Duration.between(from, to).toMinutes().toInt() else 0
        }
    }

    fun nextAll(
        now: Instant,
        zone: ZoneId,
        rules: DayRules = DayRules(),
        fixedMinutesOn: (LocalDate) -> Int = { 0 },
    ): Map<Routine, Instant> = Routine.TIMED.associateWith { next(it, now, zone, rules, fixedMinutesOn) }

    /** A local time that falls in a spring-forward gap is moved later by the gap, so it is never skipped. */
    private fun triggerOn(routine: Routine, date: LocalDate, zone: ZoneId, rules: DayRules, fixedMinutesOn: (LocalDate) -> Int): Instant? {
        val local = when (routine) {
            Routine.WORK_STOPS -> date.atTime(rules.workStopFor(fixedMinutesOn(date)))
            Routine.PLAN_READY -> date.atTime(rules.windDown)
            Routine.LIGHTS_OUT -> {
                // A lights-out after midnight belongs to the evening that started on [date].
                val day = if (rules.lightsOut < LocalTime.NOON) date.plusDays(1) else date
                day.atTime(rules.lightsOut).minusMinutes(LIGHTS_OUT_WARNING_MIN.toLong())
            }
            Routine.SUNDAY_REVIEW -> if (date.dayOfWeek == DayOfWeek.SUNDAY) date.atTime(SUNDAY_REVIEW_AT) else return null
            else -> error("$routine is not a timed routine")
        }
        return ZonedDateTime.of(local, zone).toInstant()
    }
}
