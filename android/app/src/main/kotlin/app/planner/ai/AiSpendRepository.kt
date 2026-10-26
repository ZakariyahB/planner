package app.planner.ai

import app.planner.data.SettingsRepository
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * This month's AI spend in pounds, for display. The proxy reports its running total with every call and
 * that total is kept here; the proxy is what actually enforces the cap.
 */
@Singleton
class AiSpendRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    val thisMonthGbp: Flow<Double> = settings.observe(KEY).map { stored -> parse(stored) }

    suspend fun record(report: UsageReport) {
        val month = YearMonth.now(clock).toString()
        val known = parse(settings.get(KEY))
        // The server's total is authoritative, but never let the shown figure go backwards.
        settings.put(KEY, "$month:${maxOf(report.monthGbp, known)}")
    }

    private fun parse(stored: String?): Double {
        val (month, amount) = stored?.split(":", limit = 2)?.takeIf { it.size == 2 } ?: return 0.0
        return if (month == YearMonth.now(clock).toString()) amount.toDoubleOrNull() ?: 0.0 else 0.0
    }

    companion object {
        const val KEY = "ai_spend"

        /** Shown beside the spend; the proxy's own MONTHLY_CAP_GBP is the one that is enforced. */
        const val CAP_GBP = 15.0
    }
}
