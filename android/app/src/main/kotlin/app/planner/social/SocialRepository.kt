package app.planner.social

import app.planner.data.SettingsRepository
import app.planner.data.SocialUsageDao
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** One day of social-media use against its budget. */
data class SocialDay(val date: LocalDate, val minutes: Int, val budgetMin: Int, val perApp: Map<String, Int>)

@Singleton
class SocialRepository @Inject constructor(
    private val dao: SocialUsageDao,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val budget: Flow<SocialBudget> = combine(settings.observe(KEY_WEEKDAY), settings.observe(KEY_WEEKEND)) { weekday, weekend ->
        SocialBudget(
            weekdayMin = weekday?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_WEEKDAY,
            weekendMin = weekend?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_WEEKEND,
        )
    }

    /** The last [days] days ending today, oldest first, with days of no use filled in as zero. */
    fun lastDays(days: Int): Flow<List<SocialDay>> {
        val today = LocalDate.now(clock)
        val from = today.minusDays(days - 1L)
        return combine(dao.observeBetween(from.toString(), today.toString()), budget) { rows, budget ->
            val byDate = rows.associateBy { it.date }
            (0 until days).map { i ->
                val date = from.plusDays(i.toLong())
                val row = byDate[date.toString()]
                SocialDay(date, row?.minutes ?: 0, budget.minutesFor(date), row?.let { perApp(it.perAppJson) }.orEmpty())
            }
        }
    }

    suspend fun recent(days: Int): List<SocialDay> = lastDays(days).first()

    val today: Flow<SocialDay> = lastDays(1).map { it.last() }

    private fun perApp(text: String): Map<String, Int> = try {
        json.decodeFromString<Map<String, Int>>(text)
    } catch (e: kotlinx.serialization.SerializationException) {
        emptyMap()
    }

    companion object {
        const val KEY_WEEKDAY = "social_budget_weekday_min"
        const val KEY_WEEKEND = "social_budget_weekend_min"
        const val DEFAULT_WEEKDAY = 120
        const val DEFAULT_WEEKEND = 180
    }
}
