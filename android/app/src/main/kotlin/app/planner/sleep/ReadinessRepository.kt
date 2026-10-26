package app.planner.sleep

import app.planner.data.BlockDao
import app.planner.data.ReadinessDao
import app.planner.data.SleepDao
import app.planner.data.TaskRepository
import app.planner.data.toEntity
import app.planner.data.toReadiness
import app.planner.data.toSession
import app.planner.model.TaskStatus
import app.planner.readiness.Readiness
import app.planner.readiness.ReadinessBand
import app.planner.readiness.ReadinessEngine
import app.planner.readiness.ReadinessState
import app.planner.readiness.SleepGrouping
import app.planner.readiness.SleepNight
import app.planner.scheduler.DayRules
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** Tomorrow's score if lights-out is on time, against his usual (median) lights-out. */
data class Forecast(val onTime: Readiness, val usual: Readiness?, val usualLightsOut: LocalTime?)

data class ReadinessView(
    val state: ReadinessState,
    /** True the first time today's score became final; the plan should be rebuilt for the new load. */
    val newlyFinal: Boolean,
    val forecast: Forecast?,
    /** Last night as asleep and awake periods, once any sleep is on record. */
    val night: SleepNight?,
)

@Singleton
class ReadinessRepository @Inject constructor(
    private val sleep: SleepDao,
    private val readinessDao: ReadinessDao,
    private val blocks: BlockDao,
    private val tasks: TaskRepository,
    private val clock: Clock,
) {
    private val rules = DayRules()

    /** A stored final score is returned as is; anything else is computed from the sleep on record. */
    suspend fun evaluate(awakeConfirmed: Boolean = false): ReadinessView {
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val nights = nights()
        val doneRatio = doneRatio(today.minusDays(1))

        val stored = readinessDao.get(today.toString())
        val (state, newlyFinal) = if (stored != null) {
            ReadinessState.Final(stored.toReadiness()) to false
        } else {
            val computed = ReadinessEngine.evaluate(now, clock.zone, rules.wake, nights, doneRatio, awakeConfirmed)
            if (computed is ReadinessState.Final) {
                readinessDao.put(computed.readiness.toEntity(today.toString(), now.toEpochMilli()))
            }
            computed to (computed is ReadinessState.Final)
        }
        return ReadinessView(state, newlyFinal, forecast(nights, today, doneRatio = doneRatio(today)), nights.firstOrNull { it.date == today })
    }

    /** The last [count] nights on record, oldest first. */
    suspend fun recentNights(count: Int): List<SleepNight> = nights().takeLast(count)

    /** The final score stored for [date], if that night was scored. */
    suspend fun finalFor(date: LocalDate): Readiness? = readinessDao.get(date.toString())?.toReadiness()

    /** The band that sets today's load, once the night is over; null while provisional or waiting. */
    suspend fun finalBandToday(): ReadinessBand? =
        readinessDao.get(LocalDate.now(clock).toString())?.band?.let(ReadinessBand::valueOf)

    private suspend fun nights(): List<SleepNight> {
        val since = clock.instant().minus(Duration.ofDays(21)).toEpochMilli()
        return SleepGrouping.group(sleep.since(since).map { it.toSession() }, clock.zone)
    }

    /** Shown from 23:30: what tomorrow's score looks like for lights-out at 00:15 against his usual time. */
    private fun forecast(nights: List<SleepNight>, today: LocalDate, doneRatio: Double?): Forecast? {
        if (LocalTime.now(clock) < FORECAST_FROM) return null
        val onTime = ReadinessEngine.forecast(rules.lightsOut, rules.wake, today, nights, doneRatio)
        val usualTime = ReadinessEngine.medianLightsOut(nights.takeLast(7), clock.zone)
        val usual = usualTime?.let { ReadinessEngine.forecast(it, rules.wake, today, nights, doneRatio) }
        return Forecast(onTime, usual, usualTime)
    }

    /** Share of the day's planned task minutes that got done; null if nothing was planned. */
    private suspend fun doneRatio(date: LocalDate): Double? {
        val day = blocks.between(date.toString(), date.toString()).filter { it.kind == "TASK" }
        val planned = day.sumOf { it.endMin - it.startMin }
        if (planned == 0) return null
        val done = tasks.snapshot().filter { it.status == TaskStatus.DONE }.map { it.id }.toSet()
        return day.filter { it.taskId in done }.sumOf { it.endMin - it.startMin }.toDouble() / planned
    }

    private companion object {
        val FORECAST_FROM: LocalTime = LocalTime.of(23, 30)
    }
}
