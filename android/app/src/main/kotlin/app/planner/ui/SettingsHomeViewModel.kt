package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.BuildConfig
import app.planner.ai.AiSpendRepository
import app.planner.calendar.FixedEvents
import app.planner.data.DayRulesRepository
import app.planner.data.SettingsRepository
import app.planner.data.ThemeRepository
import app.planner.sleep.HealthStatus
import app.planner.sleep.SleepRepository
import app.planner.setup.SettingsSummaries
import app.planner.setup.SleepSource
import app.planner.social.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The one-line status under each row of the Settings home. */
data class SettingsHomeUi(
    val dayRules: String = "",
    val template: String = "",
    val calendars: String = "",
    val sleep: String = "",
    val social: String = "",
    val appearance: String = "",
    val assistant: String = "",
    val spendGbp: Double = 0.0,
    val assistantConfigured: Boolean = false,
)

@HiltViewModel
class SettingsHomeViewModel @Inject constructor(
    dayRules: DayRulesRepository,
    theme: ThemeRepository,
    settings: SettingsRepository,
    social: SocialRepository,
    spend: AiSpendRepository,
    private val sleep: SleepRepository,
) : ViewModel() {
    private val sleepSource = MutableStateFlow(SleepSource.UNKNOWN)

    private class Lists(val dayRules: String, val template: String, val calendarsTotal: Int, val calendarsSelected: Int, val appearance: String)

    private val rulesAndLists = combine(
        combine(dayRules.rules, settings.template, settings.calendars, settings.selectedCalendarIds) { rules, template, calendars, selected ->
            val chosen = selected ?: FixedEvents.defaultSelection(calendars)
            Lists(
                SettingsSummaries.dayRules(rules),
                SettingsSummaries.template(template.items.size),
                calendars.size,
                calendars.count { it.id in chosen },
                "",
            )
        },
        theme.mode,
    ) { lists, mode -> Lists(lists.dayRules, lists.template, lists.calendarsTotal, lists.calendarsSelected, mode.label) }

    val ui: StateFlow<SettingsHomeUi> = combine(rulesAndLists, social.budget, spend.thisMonthGbp, settings.lastSyncedAt, sleepSource) { lists, budget, spent, lastSynced, source ->
        val configured = BuildConfig.WORKER_URL.isNotBlank() && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
        SettingsHomeUi(
            dayRules = lists.dayRules,
            template = lists.template,
            calendars = SettingsSummaries.calendars(connected = lastSynced != null, selected = lists.calendarsSelected, total = lists.calendarsTotal),
            appearance = lists.appearance,
            sleep = SettingsSummaries.sleep(source),
            social = SettingsSummaries.socialBudget(budget.weekdayMin, budget.weekendMin),
            assistant = SettingsSummaries.assistant(configured, spent, AiSpendRepository.CAP_GBP),
            spendGbp = spent,
            assistantConfigured = configured,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsHomeUi())

    init {
        refreshSleepStatus()
    }

    /** Health Connect's permission can change outside the app, so look again whenever Settings opens. */
    fun refreshSleepStatus() {
        viewModelScope.launch {
            sleepSource.value = when (sleep.healthStatus()) {
                HealthStatus.Ready -> SleepSource.CONNECTED
                HealthStatus.NeedsPermission -> SleepSource.NEEDS_PERMISSION
                is HealthStatus.Unavailable -> SleepSource.UNAVAILABLE
            }
        }
    }
}
