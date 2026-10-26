package app.planner.data

import app.planner.scheduler.DayRules
import app.planner.scheduler.DayRulesCodec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The user's day rules (wake, work stop, lights-out and so on), kept in settings. The defaults apply until edited. */
@Singleton
class DayRulesRepository @Inject constructor(private val settings: SettingsRepository) {
    val rules: Flow<DayRules> = settings.observe(KEY).map(DayRulesCodec::decode)

    suspend fun current(): DayRules = DayRulesCodec.decode(settings.get(KEY))

    suspend fun save(rules: DayRules) = settings.put(KEY, DayRulesCodec.encode(rules))

    suspend fun reset() = settings.put(KEY, "")

    private companion object {
        const val KEY = "day_rules"
    }
}
