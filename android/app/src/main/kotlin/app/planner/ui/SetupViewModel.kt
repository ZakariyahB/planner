package app.planner.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.data.SettingsRepository
import app.planner.model.Weeks
import app.planner.routines.AlarmClock
import app.planner.setup.SetupChecklist
import app.planner.setup.SetupItem
import app.planner.setup.SetupState
import app.planner.sleep.HealthStatus
import app.planner.sleep.SleepRepository
import app.planner.social.UsageAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
    private val sleep: SleepRepository,
    private val usage: UsageAccess,
    private val clock: Clock,
) : ViewModel() {
    /** What only the phone can tell us. Changes outside the app, so it is read again whenever the app returns. */
    private data class PhoneState(val health: HealthStatus, val notifications: Boolean, val exactAlarms: Boolean, val usageAccess: Boolean)

    private val phone = MutableStateFlow<PhoneState?>(null)

    /** The checklist, or null until the phone has been asked once, so the card never flashes in and out. */
    val items: StateFlow<List<SetupItem>?> = combine(settings.lastSyncedAt, settings.fajrWeeks, phone) { lastSynced, fajr, state ->
        state?.let {
            SetupChecklist.items(
                SetupState(
                    calendarConnected = lastSynced != null,
                    healthConnected = it.health == HealthStatus.Ready,
                    fajrSet = Weeks.mondayOf(LocalDate.now(clock)) in fajr,
                    notificationsAllowed = it.notifications,
                    exactAlarmsAllowed = it.exactAlarms,
                    usageAccessGranted = it.usageAccess,
                    healthAvailable = it.health !is HealthStatus.Unavailable,
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Set when a settings screen can't be opened on this phone, with where to look instead. */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val health = try {
                sleep.healthStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HealthStatus.Unavailable("Couldn't reach Health Connect") // not asked for, like a phone without it
            }
            phone.value = PhoneState(
                health = health,
                notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                exactAlarms = AlarmClock.canScheduleExact(context),
                usageAccess = usage.isGranted(),
            )
        }
    }

    fun openNotificationSettings() = open(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        "Open your phone's Settings, then Apps, Planner, Notifications, and switch them on.",
    )

    fun openExactAlarmSettings() = open(
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)),
        "Open your phone's Settings, then Apps, Planner, and allow alarms and reminders.",
    )

    fun openUsageAccess() {
        _note.value = null
        if (!usage.openSettings()) _note.value = "Open your phone's Settings, then Apps, Special access, Usage access, and choose Planner."
    }

    private fun open(intent: Intent, fallbackAdvice: String) {
        _note.value = null
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            _note.value = fallbackAdvice
        }
    }
}
