package app.planner.readiness

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Every tunable number of the heuristic lives here so it can be changed in one place. */
object ReadinessWeights {
    const val SLEEP = 40
    const val DEBT = 25
    const val LIGHTS_OUT = 20
    const val DONE = 15

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

data class ReadinessInputs(
    val asleepMin: Int,
    val debtMin: Int,
    val lightsOutDelayMin: Int,
    /** Share of yesterday's planned minutes that got done; null if nothing was planned. */
    val doneRatio: Double?,
)

data class Readiness(val score: Int, val band: ReadinessBand, val inputs: ReadinessInputs)

data class BackToBedAdvice(val sleepMin: Int, val alarm: LocalTime)

sealed interface ReadinessState {
    /** No sleep data for last night yet. Never scores an incomplete night. */
    data object Waiting : ReadinessState

    /** First block has ended; the second may still come. Not used to set the day's load. */
    data class Provisional(val readiness: Readiness, val asleepSoFarMin: Int, val advice: BackToBedAdvice?) : ReadinessState

    /** The night is over; fixed for the day. */
    data class Final(val readiness: Readiness) : ReadinessState
}

object ReadinessEngine {
    private val FINAL_FROM: LocalTime = LocalTime.of(7, 0)
    private val FINAL_FALLBACK: LocalTime = LocalTime.of(10, 0)
    private val SETTLE: Duration = Duration.ofMinutes(30)
    private const val DAY_ANCHOR_MIN = 18 * 60

    fun score(inputs: ReadinessInputs): Readiness {
        val w = ReadinessWeights
        val sleep = (inputs.asleepMin.toDouble() / w.NEED_MIN).coerceIn(0.0, 1.0)
        val debt = 1.0 - (inputs.debtMin.toDouble() / w.DEBT_ZERO_AT_MIN).coerceIn(0.0, 1.0)
        val lightsOut = 1.0 - (inputs.lightsOutDelayMin.toDouble() / w.LIGHTS_OUT_ZERO_AT_MIN).coerceIn(0.0, 1.0)
        val done = (inputs.doneRatio ?: w.NEUTRAL_DONE).coerceIn(0.0, 1.0)
        val total = w.SLEEP * sleep + w.DEBT * debt + w.LIGHTS_OUT * lightsOut + w.DONE * done
        val rounded = Math.round(total).toInt()
        return Readiness(rounded, ReadinessBand.of(rounded), inputs)
    }

    /** Minutes the lights-out time falls after the target; 0 if on time or early. */
    fun lightsOutDelayMin(lightsOut: LocalTime): Int {
        fun sinceEvening(t: LocalTime) = (t.hour * 60 + t.minute - DAY_ANCHOR_MIN + 24 * 60) % (24 * 60)
        return (sinceEvening(lightsOut) - sinceEvening(ReadinessWeights.TARGET_LIGHTS_OUT)).coerceAtLeast(0)
    }

    /** Shortfall against the need over the seven nights up to [upTo], scaled up if fewer nights are known. */
    fun sleepDebtMin(nights: List<SleepNight>, upTo: LocalDate): Int {
        val window = nights.filter { it.date <= upTo && it.date > upTo.minusDays(7) }
        if (window.isEmpty()) return 0
        val debt = window.sumOf { (ReadinessWeights.NEED_MIN - it.asleepMin).coerceAtLeast(0) }
        return debt * 7 / window.size
    }

    fun inputsFor(night: SleepNight, nights: List<SleepNight>, doneRatio: Double?, zone: ZoneId) = ReadinessInputs(
        asleepMin = night.asleepMin,
        debtMin = sleepDebtMin(nights, night.date),
        lightsOutDelayMin = lightsOutDelayMin(night.lightsOut.atZone(zone).toLocalTime()),
        doneRatio = doneRatio,
    )

    /**
     * What to show this morning. Final means the night is over, so the day's load is fixed:
     * - [awakeConfirmed] (phone unlocked) after 07:00, or
     * - the last sleep ended after 07:00 and 30 minutes have passed, or
     * - the 10:00 fallback.
     * A last sleep that ended around Fajr is not enough on its own: he may be back in bed with the
     * second block not yet written, so the score stays provisional until one of the above.
     */
    fun evaluate(
        now: Instant,
        zone: ZoneId,
        wake: LocalTime,
        nights: List<SleepNight>,
        doneRatio: Double?,
        awakeConfirmed: Boolean = false,
    ): ReadinessState {
        val local = now.atZone(zone)
        val night = nights.firstOrNull { it.date == local.toLocalDate() && it.lastEnd <= now } ?: return ReadinessState.Waiting
        val readiness = score(inputsFor(night, nights, doneRatio, zone))
        val time = local.toLocalTime()
        val lastEndedInMorning = night.lastEnd.atZone(zone).toLocalTime() >= FINAL_FROM
        val over = time >= FINAL_FALLBACK ||
            (time >= FINAL_FROM && (awakeConfirmed || (lastEndedInMorning && now >= night.lastEnd.plus(SETTLE))))
        if (over) return ReadinessState.Final(readiness)
        return ReadinessState.Provisional(readiness, night.asleepMin, advice(now, zone, wake, night.asleepMin))
    }

    /** Time left to sleep before the wake alarm, capped at what is still needed. Null if the alarm time has passed. */
    fun advice(now: Instant, zone: ZoneId, wake: LocalTime, asleepSoFar: Int): BackToBedAdvice? {
        val local = now.atZone(zone)
        val available = Duration.between(local.toLocalTime(), wake).toMinutes().toInt()
        if (available <= 0) return null
        val needed = (ReadinessWeights.NEED_MIN - asleepSoFar).coerceAtLeast(0)
        return BackToBedAdvice(minOf(needed, available), wake)
    }

    /** Median lights-out of the given nights, or null with none. */
    fun medianLightsOut(nights: List<SleepNight>, zone: ZoneId): LocalTime? {
        if (nights.isEmpty()) return null
        val minutes = nights.map { n ->
            val t = n.lightsOut.atZone(zone).toLocalTime()
            (t.hour * 60 + t.minute - DAY_ANCHOR_MIN + 24 * 60) % (24 * 60)
        }.sorted()
        val mid = minutes[minutes.size / 2]
        return LocalTime.ofSecondOfDay(((mid + DAY_ANCHOR_MIN) % (24 * 60)) * 60L)
    }

    /** The 23:30 forecast: tomorrow's score if lights-out happens at [lightsOut] and he wakes at [wake]. */
    fun forecast(lightsOut: LocalTime, wake: LocalTime, tonight: LocalDate, nights: List<SleepNight>, doneRatio: Double?): Readiness {
        val inBed = Duration.between(lightsOut, wake).let { if (it.isNegative) it.plusDays(1) else it }.toMinutes().toInt()
        val asleep = (inBed * ReadinessWeights.SLEEP_EFFICIENCY).toInt()
        val tomorrow = tonight.plusDays(1)
        val earlier = nights.filter { it.date < tomorrow }
        val debt = earlier.filter { it.date > tomorrow.minusDays(7) }.let { window ->
            val sum = window.sumOf { (ReadinessWeights.NEED_MIN - it.asleepMin).coerceAtLeast(0) } +
                (ReadinessWeights.NEED_MIN - asleep).coerceAtLeast(0)
            sum * 7 / (window.size + 1)
        }
        return score(ReadinessInputs(asleep, debt, lightsOutDelayMin(lightsOut), doneRatio))
    }
}
