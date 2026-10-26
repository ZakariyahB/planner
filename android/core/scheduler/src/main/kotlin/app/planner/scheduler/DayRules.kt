package app.planner.scheduler

import java.time.DayOfWeek
import java.time.LocalTime

/** User-editable day rules (see docs/PLAN.md "Scheduler"). Defaults are ZB's. */
data class DayRules(
    val wake: LocalTime = LocalTime.of(8, 45),
    val workStop: LocalTime = LocalTime.of(21, 0),
    val workStopPacked: LocalTime = LocalTime.of(22, 0),
    val windDown: LocalTime = LocalTime.of(23, 30),
    val lightsOut: LocalTime = LocalTime.of(0, 15),
    val blockBufferMin: Int = 15,
    val prayerBufferMin: Int = 15,
    val packedFixedMinutes: Int = 180,
    val fridayClearFrom: LocalTime = LocalTime.of(17, 0),
    val sundayCatchUpMin: Int = 120,
    /** Cap on planned task minutes per day, so earliest-deadline-first doesn't pile the week onto Monday. */
    val maxTaskMinutesPerDay: Int = 360,
) {
    init {
        require(blockBufferMin >= 0 && prayerBufferMin >= 0) { "buffers must be non-negative" }
        require(workStop <= workStopPacked) { "packed work stop must not be earlier than normal" }
        require(maxTaskMinutesPerDay > 0) { "daily cap must be positive" }
    }

    /** Focused work stops later on days with at least [packedFixedMinutes] of fixed events. */
    fun workStopFor(fixedMinutes: Int): LocalTime =
        if (fixedMinutes >= packedFixedMinutes) workStopPacked else workStop

    /** Latest time work may end on [day]; Friday is kept clear from [fridayClearFrom]. */
    fun workEndFor(day: DayOfWeek, fixedMinutes: Int): LocalTime {
        val stop = workStopFor(fixedMinutes)
        return if (day == DayOfWeek.FRIDAY && fridayClearFrom < stop) fridayClearFrom else stop
    }
}
