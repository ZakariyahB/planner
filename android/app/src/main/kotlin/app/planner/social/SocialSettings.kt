package app.planner.social

import app.planner.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The user's social-time choices, kept as small settings. The budget itself lives with [SocialRepository]. */
@Singleton
class SocialSettings @Inject constructor(private val settings: SettingsRepository) {
    val selection: Flow<SocialAppSelection> =
        combine(settings.observe(KEY_DISABLED), settings.observe(KEY_EXTRA)) { disabled, extra -> SocialAppSelection.from(disabled, extra) }

    /** Nudges and the summary are on until the user turns them off. */
    val nudgesOn: Flow<Boolean> = settings.observe(KEY_NUDGES).map { it != OFF }

    val summaryOn: Flow<Boolean> = settings.observe(KEY_SUMMARY).map { it != OFF }

    suspend fun packages(): Set<String> = selection.first().packages()

    suspend fun setDefaultEnabled(id: String, enabled: Boolean) = saveSelection(selection.first().withDefault(id, enabled))

    suspend fun addApp(pkg: String) = saveSelection(selection.first().withExtra(pkg))

    suspend fun removeApp(pkg: String) = saveSelection(selection.first().withoutExtra(pkg))

    suspend fun setNudgesOn(on: Boolean) = settings.put(KEY_NUDGES, if (on) ON else OFF)

    suspend fun setSummaryOn(on: Boolean) = settings.put(KEY_SUMMARY, if (on) ON else OFF)

    suspend fun saveBudget(budget: SocialBudget) {
        settings.put(SocialRepository.KEY_WEEKDAY, budget.weekdayMin.toString())
        settings.put(SocialRepository.KEY_WEEKEND, budget.weekendMin.toString())
    }

    /** What was last sent (it may be from an earlier day), or null on a fresh install or if it can't be read. */
    suspend fun sent(): SocialSent? = SocialSent.decode(settings.get(KEY_SENT))

    suspend fun saveSent(sent: SocialSent) = settings.put(KEY_SENT, sent.encode())

    private suspend fun saveSelection(selection: SocialAppSelection) {
        settings.put(KEY_DISABLED, SocialAppSelection.encode(selection.disabledDefaults))
        settings.put(KEY_EXTRA, SocialAppSelection.encode(selection.extraPackages))
    }

    private companion object {
        const val KEY_DISABLED = "social_apps_disabled"
        const val KEY_EXTRA = "social_apps_extra"
        const val KEY_NUDGES = "social_nudges_on"
        const val KEY_SUMMARY = "social_summary_on"
        const val KEY_SENT = "social_sent"
        const val ON = "1"
        const val OFF = "0"
    }
}
