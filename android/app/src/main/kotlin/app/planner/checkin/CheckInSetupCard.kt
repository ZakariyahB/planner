package app.planner.checkin

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.planner.R

/**
 * What check-ins need, for the UI (Stream D) to place on Today or Settings: notifications, exact alarms, and the
 * battery-optimisation explainer with a deep link to the setting. Shows nothing once everything is set. The battery
 * explainer is shown in full once; after "Got it" only a one-line reminder stays while optimisation is still on.
 * State is re-read every time the screen resumes, so coming back from system settings updates it.
 */
@Composable
fun CheckInSetupCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { CheckInPrefs(context.applicationContext) }
    var state by remember { mutableStateOf(CheckInPermissions.readiness(context)) }
    var explainerSeen by remember { mutableStateOf(prefs.batteryExplainerSeen) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val before = state
                state = CheckInPermissions.readiness(context)
                // Newly allowed exact alarms: set the alarms again so they become exact.
                if (!before.exactAlarms && state.exactAlarms) CheckInWork.reschedule(context.applicationContext, force = true)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        state = CheckInPermissions.readiness(context)
    }

    if (state.allSet) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.checkin_setup_title), style = MaterialTheme.typography.titleMedium)
            if (!state.notifications) {
                SetupRow(stringResource(R.string.checkin_setup_notifications), stringResource(R.string.checkin_setup_allow)) {
                    if (CheckInPermissions.needsNotificationPermission(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else CheckInPermissions.open(context, CheckInPermissions.notificationSettingsIntent(context))
                }
            }
            if (!state.exactAlarms) {
                SetupRow(stringResource(R.string.checkin_setup_exact), stringResource(R.string.checkin_setup_allow)) {
                    CheckInPermissions.open(context, CheckInPermissions.exactAlarmSettingsIntent(context))
                }
            }
            if (!state.batteryUnrestricted) {
                if (!explainerSeen) {
                    Text(stringResource(R.string.checkin_battery_explainer), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { CheckInPermissions.open(context, CheckInPermissions.batterySettingsIntent()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.checkin_battery_open))
                        }
                        TextButton(onClick = { prefs.batteryExplainerSeen = true; explainerSeen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.checkin_battery_got_it))
                        }
                    }
                } else {
                    SetupRow(stringResource(R.string.checkin_setup_battery), stringResource(R.string.checkin_battery_open)) {
                        CheckInPermissions.open(context, CheckInPermissions.batterySettingsIntent())
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupRow(text: String, button: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(button) }
    }
}
