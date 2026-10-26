package app.planner.insights

import app.planner.readiness.ReadinessWeights
import kotlin.math.roundToInt

/** The week at a glance: the headline figures and a few plain sentences worked out from the numbers. */
data class WeeklyReview(
    val avgAsleepMin: Int?,
    val avgLightsOutMin: Int?,
    val donePercent: Int?,
    val plannedMin: Int,
    val sentences: List<String>,
)

object WeeklyReviews {
    private const val LATE_NIGHT_MIN = 30
    private const val NOTICEABLE_GAP_POINTS = 10

    /** Pure arithmetic over the week's rows; the sentences are not written by the assistant. */
    fun build(sleep: List<SleepRow>, lights: List<LightsOutRow>, done: List<DoneRow>, social: List<SocialRow>): WeeklyReview {
        val need = ReadinessWeights.NEED_MIN
        val sentences = buildList {
            sleepLine(sleep, need)?.let(::add)
            afterShortNightLine(sleep, done, need)?.let(::add)
            lightsOutLine(lights)?.let(::add)
            socialLine(social)?.let(::add)
        }
        return WeeklyReview(
            avgAsleepMin = SleepInsights.averageAsleepMin(sleep),
            avgLightsOutMin = LightsOutInsights.averageMinutesAfterTarget(lights),
            donePercent = PlannedVsDone.overallPercent(done),
            plannedMin = PlannedVsDone.totalPlannedMin(done),
            sentences = sentences,
        )
    }

    private fun sleepLine(sleep: List<SleepRow>, need: Int): String? {
        if (sleep.isEmpty()) return null
        val short = sleep.count { it.asleepMin < need }
        val hours = need / 60
        return if (short == 0) "You got at least $hours hours every night. Keep that going." else "You slept under $hours hours on $short of ${sleep.size} nights."
    }

    /** Only said when the gap is real: days after a short night finished clearly less than days after a full one. */
    private fun afterShortNightLine(sleep: List<SleepRow>, done: List<DoneRow>, need: Int): String? {
        val doneByDate = done.associateBy { it.date }
        fun share(rows: List<SleepRow>): Int? {
            val days = rows.mapNotNull { doneByDate[it.date] }.filter { it.plannedMin > 0 }
            val planned = days.sumOf { it.plannedMin }
            return if (planned == 0) null else (days.sumOf { it.doneMin } * 100.0 / planned).roundToInt()
        }
        val (short, full) = sleep.partition { it.asleepMin < need }
        val afterShort = share(short) ?: return null
        val afterFull = share(full) ?: return null
        if (afterFull - afterShort < NOTICEABLE_GAP_POINTS) return null
        return "On days after a short night you finished $afterShort% of your plan, against $afterFull% after a full night."
    }

    private fun lightsOutLine(lights: List<LightsOutRow>): String? {
        val worst = lights.filter { it.minutesAfterTarget > LATE_NIGHT_MIN }.sortedByDescending { it.minutesAfterTarget }.take(2)
        if (worst.isEmpty()) return null
        return "Lights-out was latest on ${worst.joinToString(" and ") { it.weekday }}. Bed by 00:15 on those nights would help most."
    }

    private fun socialLine(social: List<SocialRow>): String? {
        if (social.isEmpty()) return null
        val over = SocialInsights.daysOver(social)
        return when (over) {
            0 -> "You stayed within your social-media budget every day."
            1 -> "You went over your social-media budget on 1 day."
            else -> "You went over your social-media budget on $over days."
        }
    }
}
