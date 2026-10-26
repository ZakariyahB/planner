package app.planner.routines

import app.planner.readiness.ReadinessWeights
import app.planner.readiness.SleepNight
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** When the two event-driven notifications are due. Pure, so the periodic job and the unlock receiver share it. */
object RoutineTriggers {
    val MORNING_FROM: LocalTime = LocalTime.of(7, 0)
    val MORNING_FALLBACK: LocalTime = LocalTime.of(10, 0)

    /** A morning brief that has not gone out by now is no longer a morning brief. */
    val MORNING_UNTIL: LocalTime = LocalTime.of(14, 0)

    private val BEFORE_FAJR: Duration = Duration.ofMinutes(90)
    private val AFTER_FAJR: Duration = Duration.ofMinutes(120)
    private val DEFAULT_FROM: LocalTime = LocalTime.of(3, 30)
    private val DEFAULT_TO: LocalTime = LocalTime.of(7, 0)

    /** A first block older than this is no longer news; he has probably got up or gone back to bed already. */
    private val STALE_AFTER: Duration = Duration.ofMinutes(90)

    /**
     * "Back to bed" advice makes sense when this morning's only sleep block so far ended around Fajr, it ended
     * recently, he has not had his 7 hours, the wake alarm is still ahead, and it has not been sent today.
     */
    fun provisionalDue(
        night: SleepNight?,
        now: Instant,
        zone: ZoneId,
        fajr: LocalTime?,
        wake: LocalTime,
        sentOn: LocalDate?,
    ): Boolean {
        if (night == null) return false
        val local = now.atZone(zone)
        val today = local.toLocalDate()
        if (night.date != today || sentOn == today) return false
        if (night.blocks.size != 1) return false
        if (night.asleepMin >= ReadinessWeights.NEED_MIN) return false
        if (local.toLocalTime() >= wake) return false

        val ended = night.firstBlockEnd
        if (ended > now || Duration.between(ended, now) > STALE_AFTER) return false

        val endedAt = ended.atZone(zone).toLocalTime()
        val from = fajr?.minus(BEFORE_FAJR) ?: DEFAULT_FROM
        val to = fajr?.plus(AFTER_FAJR) ?: DEFAULT_TO
        return endedAt >= from && endedAt <= to
    }

    /**
     * The morning brief goes out after 07:00 once the phone is unlocked, or at the 10:00 fallback, once a day and
     * not after 14:00.
     * Whether last night has a final score is decided separately by the readiness engine.
     */
    fun morningBriefDue(now: Instant, zone: ZoneId, sentOn: LocalDate?, awakeConfirmed: Boolean): Boolean {
        val local = now.atZone(zone)
        if (sentOn == local.toLocalDate()) return false
        val time = local.toLocalTime()
        if (time >= MORNING_UNTIL) return false
        return time >= MORNING_FALLBACK || (time >= MORNING_FROM && awakeConfirmed)
    }
}
