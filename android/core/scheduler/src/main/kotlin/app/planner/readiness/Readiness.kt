package app.planner.readiness

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Every tunable number of the heuristic lives here so it can be changed in one place. */
object ReadinessWeights {
    /** Sleep in the last 24 hours against the need. */
    const val SLEEP_24H = 25
    /** Sleep in the last 72 hours against three times the need. */
    const val SLEEP_72H = 15
    const val DEBT = 25
    const val LIGHTS_OUT = 20
    const val DONE = 15

    /** The default need; [ReadinessConfig.needMin] is what the engine uses. */
    const val NEED_MIN = 420
    /** Seven nights each 2h short scores zero on the debt input. */
    const val DEBT_ZERO_AT_MIN = 7 * 120
    /** Lights-out this late after the target scores zero. */
    const val LIGHTS_OUT_ZERO_AT_MIN = 120
    /** Used when nothing was planned yesterday. */
    const val NEUTRAL_DONE = 0.8
    /** Share of time in bed assumed asleep, for the 23:30 forecast. */
    const val SLEEP_EFFICIENCY = 0.85
    val TARGET_LIGHTS_OUT: LocalTime = LocalTime.of(0, 15)
}

/** The hours he usually wakes up from his main sleep. */
data class WakeWindow(val from: LocalTime, val to: LocalTime)

/**
 * Settings of the readiness engine.
 * - [needMin]: the sleep he needs in a day.
 * - The score becomes final when another sleep this morning is unlikely: he has been awake for
 *   [finalAfterAwake], or his last sleep ended inside his usual wake window (or the window has passed) and
 *   [settle] has gone by, or the phone was unlocked inside the window.
 * - The wake window is learned from the last [learnFromDays] days once [minDaysToLearn] are on record;
 *   [defaultWakeWindow] until then.
 */
data class ReadinessConfig(
    val needMin: Int = ReadinessWeights.NEED_MIN,
    val finalAfterAwake: Duration = Duration.ofHours(4),
    val settle: Duration = Duration.ofMinutes(30),
    val defaultWakeWindow: WakeWindow = WakeWindow(LocalTime.of(7, 0), LocalTime.of(10, 0)),
    val learnFromDays: Int = 14,
    val minDaysToLearn: Int = 5,
)

data class ReadinessInputs(
    /** Sleep in the 24 hours up to the main wake-up (never reaching back into the day before's main sleep). */
    val asleepMin: Int,
    /** Shortfall against the need over the last seven sleep days. */
    val debtMin: Int,
    val lightsOutDelayMin: Int,
    /** Share of yesterday's planned minutes that got done; null if nothing was planned. */
    val doneRatio: Double?,
    /** Sleep in the 72 hours up to the main wake-up; null when not known (then the 24 hours stand in). */
    val asleep72hMin: Int? = null,
    val needMin: Int = ReadinessWeights.NEED_MIN,
)

data class Readiness(val score: Int, val band: ReadinessBand, val inputs: ReadinessInputs)

data class BackToBedAdvice(val sleepMin: Int, val alarm: LocalTime)

sealed interface ReadinessState {
    /** No sleep on record for this morning yet. Never scores an incomplete night. */
    data object Waiting : ReadinessState

    /** He woke, but another sleep may still come (the Fajr wake-up). Not used to set the day's load. */
    data class Provisional(val readiness: Readiness, val asleepSoFarMin: Int, val advice: BackToBedAdvice?) : ReadinessState

    /** Another sleep this morning is unlikely; fixed for the day. */
    data class Final(val readiness: Readiness) : ReadinessState
}

object ReadinessEngine {
    private const val DAY_ANCHOR_MIN = 18 * 60

    fun score(inputs: ReadinessInputs): Readiness {
        val w = ReadinessWeights
        val need = inputs.needMin.coerceAtLeast(1).toDouble()
        val sleep24 = (inputs.asleepMin / need).coerceIn(0.0, 1.0)
        val sleep72 = inputs.asleep72hMin?.let { (it / (3 * need)).coerceIn(0.0, 1.0) } ?: sleep24
        val debt = 1.0 - (inputs.debtMin.toDouble() / w.DEBT_ZERO_AT_MIN).coerceIn(0.0, 1.0)
        val lightsOut = 1.0 - (inputs.lightsOutDelayMin.toDouble() / w.LIGHTS_OUT_ZERO_AT_MIN).coerceIn(0.0, 1.0)
        val done = (inputs.doneRatio ?: w.NEUTRAL_DONE).coerceIn(0.0, 1.0)
        val total = w.SLEEP_24H * sleep24 + w.SLEEP_72H * sleep72 + w.DEBT * debt + w.LIGHTS_OUT * lightsOut + w.DONE * done
        val rounded = Math.round(total).toInt()
        return Readiness(rounded, ReadinessBand.of(rounded), inputs)
    }

    /** A settled score is worked out again when the sleep on record has changed by this much since. */
    const val CORRECTION_MIN = 10

    /**
     * Whether the sleep now on record differs enough from what the stored score used that the score should be
     * redone: Health Connect data arrived late, or was read differently by a newer version of the app.
     */
    fun sleepCorrected(storedAsleepMin: Int, nowAsleepMin: Int?): Boolean =
        nowAsleepMin != null && Math.abs(nowAsleepMin - storedAsleepMin) >= CORRECTION_MIN

    /** Minutes the lights-out time falls after the target; 0 if on time or early. */
    fun lightsOutDelayMin(lightsOut: LocalTime): Int =
        (sinceEvening(lightsOut) - sinceEvening(ReadinessWeights.TARGET_LIGHTS_OUT)).coerceAtLeast(0)

    /** Shortfall against the need over the seven sleep days up to [upTo], scaled up if fewer days are known. */
    fun sleepDebtMin(days: List<SleepDay>, upTo: LocalDate, needMin: Int = ReadinessWeights.NEED_MIN): Int {
        val window = days.filter { it.wakeDate <= upTo && it.wakeDate > upTo.minusDays(7) }
        if (window.isEmpty()) return 0
        val debt = window.sumOf { (needMin - it.totalAsleepMin).coerceAtLeast(0) }
        return debt * 7 / window.size
    }

    /**
     * Sleep in the [hours] up to [day]'s main wake-up. The window never reaches back past an earlier main wake-up
     * (24 hours: yesterday's; 72 hours: the one three days back), so waking earlier than the day before does not
     * pull the end of that day's sleep in again.
     */
    fun rollingAsleepMin(day: SleepDay, days: List<SleepDay>, hours: Long): Int {
        val at = day.mainWake
        val earlier = days.filter { it.mainWake < day.lightsOut }.sortedBy { it.mainWake }
        val back = (hours / 24).toInt()
        val floor = earlier.getOrNull(earlier.size - back)?.mainWake
        val from = listOfNotNull(at.minus(Duration.ofHours(hours)), floor).max()
        return asleepBetween(days.flatMap { it.episodes }, from, at)
    }

    fun inputsFor(day: SleepDay, days: List<SleepDay>, doneRatio: Double?, zone: ZoneId, config: ReadinessConfig = ReadinessConfig()) =
        ReadinessInputs(
            asleepMin = rollingAsleepMin(day, days, 24),
            debtMin = sleepDebtMin(days, day.wakeDate, config.needMin),
            lightsOutDelayMin = lightsOutDelayMin(day.lightsOut.atZone(zone).toLocalTime()),
            doneRatio = doneRatio,
            asleep72hMin = rollingAsleepMin(day, days, 72),
            needMin = config.needMin,
        )

    /** The usual wake-up window, learned from the main wake-ups of the days before [today]. */
    fun wakeWindow(days: List<SleepDay>, today: LocalDate, zone: ZoneId, config: ReadinessConfig = ReadinessConfig()): WakeWindow {
        val wakes = days.filter { it.wakeDate < today && it.wakeDate >= today.minusDays(config.learnFromDays.toLong()) }
            .map { it.mainWake.atZone(zone).toLocalTime().let { t -> t.hour * 60 + t.minute } }
            .sorted()
        if (wakes.size < config.minDaysToLearn) return config.defaultWakeWindow
        val q1 = wakes[(wakes.size - 1) / 4]
        val q3 = wakes[(3 * (wakes.size - 1) + 3) / 4]
        fun time(min: Int) = LocalTime.of(min.coerceIn(0, 24 * 60 - 1) / 60, min.coerceIn(0, 24 * 60 - 1) % 60)
        return WakeWindow(time(q1 - 30), time(q3 + 60))
    }

    /**
     * Whether another sleep this morning is unlikely, so [day]'s score can be fixed: he has been awake for
     * [ReadinessConfig.finalAfterAwake]; or, once [ReadinessConfig.settle] has passed (or the phone was unlocked),
     * the last sleep ended inside his usual wake window, or the window is over, or he unlocked the phone inside it.
     * A last sleep that ended at Fajr is not enough on its own: he may be back in bed with the second block not
     * yet written.
     */
    fun isFinal(day: SleepDay, days: List<SleepDay>, now: Instant, zone: ZoneId, awakeConfirmed: Boolean, config: ReadinessConfig = ReadinessConfig()): Boolean {
        val last = day.lastEnd
        if (last > now) return false
        val awake = Duration.between(last, now)
        if (awake >= config.finalAfterAwake) return true
        val window = wakeWindow(days, day.wakeDate, zone, config)
        val nowTime = now.atZone(zone).toLocalTime()
        val settled = awake >= config.settle || awakeConfirmed
        val endedInMorning = last.atZone(zone).toLocalTime() >= window.from
        return settled && (endedInMorning || nowTime >= window.to || (awakeConfirmed && nowTime >= window.from))
    }

    /** What to show this morning: waiting for sleep, provisional (another sleep may come) or final. */
    fun evaluate(
        now: Instant,
        zone: ZoneId,
        wake: LocalTime,
        days: List<SleepDay>,
        doneRatio: Double?,
        awakeConfirmed: Boolean = false,
        config: ReadinessConfig = ReadinessConfig(),
    ): ReadinessState {
        val today = now.atZone(zone).toLocalDate()
        val known = days.mapNotNull { d -> trimmed(d, now) }
        val day = known.firstOrNull { it.wakeDate == today } ?: return ReadinessState.Waiting
        val readiness = score(inputsFor(day, known, doneRatio, zone, config))
        if (isFinal(day, known, now, zone, awakeConfirmed, config)) return ReadinessState.Final(readiness)
        return ReadinessState.Provisional(readiness, day.totalAsleepMin, advice(now, zone, wake, day.totalAsleepMin, config.needMin))
    }

    /** [day] with only the sleep that had ended by [now]; null if none had. */
    private fun trimmed(day: SleepDay, now: Instant): SleepDay? {
        if (day.lastEnd <= now) return day
        val periods = day.periods.map { p -> p.filter { it.end <= now } }
        if (periods[day.mainIndex].isEmpty()) return null
        val keep = periods.withIndex().filter { it.value.isNotEmpty() }
        return SleepDay(day.wakeDate, keep.map { it.value }, keep.indexOfFirst { it.index == day.mainIndex })
    }

    /** Time left to sleep before the wake alarm, capped at what is still needed. Null if the alarm time has passed. */
    fun advice(now: Instant, zone: ZoneId, wake: LocalTime, asleepSoFar: Int, needMin: Int = ReadinessWeights.NEED_MIN): BackToBedAdvice? {
        val local = now.atZone(zone)
        val available = Duration.between(local.toLocalTime(), wake).toMinutes().toInt()
        if (available <= 0) return null
        val needed = (needMin - asleepSoFar).coerceAtLeast(0)
        return BackToBedAdvice(minOf(needed, available), wake)
    }

    /** Median lights-out of the given days' main sleeps, or null with none. */
    fun medianLightsOut(days: List<SleepDay>, zone: ZoneId): LocalTime? {
        if (days.isEmpty()) return null
        val minutes = days.map { sinceEvening(it.lightsOut.atZone(zone).toLocalTime()) }.sorted()
        val mid = minutes[minutes.size / 2]
        return LocalTime.ofSecondOfDay(((mid + DAY_ANCHOR_MIN) % (24 * 60)) * 60L)
    }

    /** The 23:30 forecast: tomorrow's score if lights-out happens at [lightsOut] and he wakes at [wake]. */
    fun forecast(
        lightsOut: LocalTime,
        wake: LocalTime,
        tonight: LocalDate,
        days: List<SleepDay>,
        doneRatio: Double?,
        config: ReadinessConfig = ReadinessConfig(),
    ): Readiness {
        val inBed = Duration.between(lightsOut, wake).let { if (it.isNegative) it.plusDays(1) else it }.toMinutes().toInt()
        val asleep = (inBed * ReadinessWeights.SLEEP_EFFICIENCY).toInt()
        val tomorrow = tonight.plusDays(1)
        val need = config.needMin
        val week = days.filter { it.wakeDate < tomorrow && it.wakeDate > tomorrow.minusDays(7) }
        val debt = (week.sumOf { (need - it.totalAsleepMin).coerceAtLeast(0) } + (need - asleep).coerceAtLeast(0)) * 7 / (week.size + 1)
        val asleep72 = asleep + days.filter { it.wakeDate < tomorrow && it.wakeDate > tomorrow.minusDays(3) }.sumOf { it.totalAsleepMin }
        return score(ReadinessInputs(asleep, debt, lightsOutDelayMin(lightsOut), doneRatio, asleep72, need))
    }

    private fun sinceEvening(t: LocalTime) = (t.hour * 60 + t.minute - DAY_ANCHOR_MIN + 24 * 60) % (24 * 60)
}
