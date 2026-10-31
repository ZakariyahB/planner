package app.planner.sleep

import app.planner.data.BlockDao
import app.planner.data.DayRulesRepository
import app.planner.data.ReadinessDao
import app.planner.data.SleepDao
import app.planner.data.TaskRepository
import app.planner.data.toEntity
import app.planner.data.toReadiness
import app.planner.data.toSession
import app.planner.model.TaskStatus
import app.planner.readiness.Readiness
import app.planner.readiness.ReadinessBand
import app.planner.readiness.ReadinessConfig
import app.planner.readiness.ReadinessEngine
import app.planner.readiness.ReadinessState
import app.planner.readiness.SleepDay
import app.planner.readiness.SleepDays
import app.planner.readiness.SleepNight
import app.planner.readiness.SleepSummaries
import app.planner.readiness.SleepSummary
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
    /** This morning's sleep day as a night (main sleep as blocks, later sleep as extras), once any is on record. */
    val night: SleepNight?,
    /** The last seven nights on record, oldest first: when he slept and woke, the interruptions and the total. */
    val recentNights: List<SleepNight> = emptyList(),
    /** This morning's sleep day: every episode, total, core and extra. */
    val day: SleepDay? = null,
)

@Singleton
class ReadinessRepository @Inject constructor(
    private val sleep: SleepDao,
    private val readinessDao: ReadinessDao,
    private val blocks: BlockDao,
    private val tasks: TaskRepository,
    private val dayRules: DayRulesRepository,
    private val clock: Clock,
) {

    /**
     * A stored final score is returned as is, unless the sleep on record for the day has since been corrected
     * by [ReadinessEngine.CORRECTION_MIN] minutes or more (Health Connect data arrived late or was read differently): then the
     * score is worked out again from what is on record. Anything not yet final is computed.
     */
    suspend fun evaluate(awakeConfirmed: Boolean = false): ReadinessView {
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val rules = dayRules.current()
        val days = days()
        val doneRatio = doneRatio(today.minusDays(1))

        val stored = readinessDao.get(today.toString())?.toReadiness()
        val todayDay = days.firstOrNull { it.wakeDate == today && it.mainWake <= now }
        val fresh = todayDay?.let { ReadinessEngine.inputsFor(it, days, doneRatio, clock.zone, config) }
        val corrected = stored != null && ReadinessEngine.sleepCorrected(stored.inputs.asleepMin, fresh?.asleepMin)
        val (state, newlyFinal) = when {
            stored != null && !corrected -> ReadinessState.Final(stored) to false
            // A stored score means another sleep was already unlikely, so a correction is rescored as final.
            stored != null && fresh != null -> {
                val rescored = ReadinessEngine.score(fresh)
                readinessDao.put(rescored.toEntity(today.toString(), now.toEpochMilli()))
                ReadinessState.Final(rescored) to true
            }
            else -> {
                val computed = ReadinessEngine.evaluate(now, clock.zone, rules.wake, days, doneRatio, awakeConfirmed, config)
                if (computed is ReadinessState.Final) {
                    readinessDao.put(computed.readiness.toEntity(today.toString(), now.toEpochMilli()))
                    computed to true
                } else {
                    computed to false
                }
            }
        }
        val nights = days.map { it.toNight() }
        return ReadinessView(
            state = state,
            newlyFinal = newlyFinal,
            forecast = forecast(days, today, rules, doneRatio = doneRatio(today)),
            night = todayDay?.toNight(),
            recentNights = nights.takeLast(RECENT_NIGHTS),
            day = todayDay,
        )
    }

    /** The last [count] nights on record, oldest first. */
    suspend fun recentNights(count: Int): List<SleepNight> = days().takeLast(count).map { it.toNight() }

    /** The last [count] sleep days on record, oldest first. */
    suspend fun recentDays(count: Int): List<SleepDay> = days().takeLast(count)

    /** Rolling totals, debt and the last seven sleep days in a few numbers, for the assistant. */
    suspend fun sleepSummary(): SleepSummary = SleepSummaries.summarise(days(), clock.instant(), clock.zone, config.needMin)

    /** The final score stored for [date], if that day was scored. */
    suspend fun finalFor(date: LocalDate): Readiness? = readinessDao.get(date.toString())?.toReadiness()

    /** The band that sets today's load, once the score is final; null while provisional or waiting. */
    suspend fun finalBandToday(): ReadinessBand? =
        readinessDao.get(LocalDate.now(clock).toString())?.band?.let(ReadinessBand::valueOf)

    /** Every record of the last three weeks (Health Connect rows were already filtered by source at sync) as sleep days. */
    private suspend fun days(): List<SleepDay> {
        val since = clock.instant().minus(Duration.ofDays(21)).toEpochMilli()
        return SleepDays.build(sleep.since(since).map { it.toSession() }, clock.zone)
    }

    /** Shown from 23:30: what tomorrow's score looks like for lights-out at 00:15 against his usual time. */
    private fun forecast(days: List<SleepDay>, today: LocalDate, rules: DayRules, doneRatio: Double?): Forecast? {
        if (LocalTime.now(clock) < FORECAST_FROM) return null
        val onTime = ReadinessEngine.forecast(rules.lightsOut, rules.wake, today, days, doneRatio, config)
        val usualTime = ReadinessEngine.medianLightsOut(days.takeLast(7), clock.zone)
        val usual = usualTime?.let { ReadinessEngine.forecast(it, rules.wake, today, days, doneRatio, config) }
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
        const val RECENT_NIGHTS = 7

        /** The default need (7h) and finality rules until Settings carries a sleep need (see docs/redesign/CHANGES.md). */
        val config = ReadinessConfig()
    }
}
