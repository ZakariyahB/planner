package app.planner.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.routines.AlarmClock
import app.planner.routines.Routine
import app.planner.routines.info
import app.planner.sleep.SleepRepository

/** Settings → Routines: which notifications to get, and whether the system lets them through. */
@Composable
fun RoutineSettingsSection(vm: RoutinePrefsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val enabled by vm.enabled.collectAsStateWithLifecycle()

    // Permissions are changed in system screens; look again when the user comes back.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh++
        vm.reschedule() // picks up an exact-alarm permission granted while the app was in the background
    }
    val notificationsOn = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exactAlarms = remember(refresh) { AlarmClock.canScheduleExact(context) }
    var backgroundSleep by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(refresh) { backgroundSleep = vm.backgroundSleepGranted() }
    val healthBackground = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh++ }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.markPromptSeen()
        refresh++
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Routines")
        Text(
            "Each routine has its own notification channel as well, so you can also mute one from the system settings.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!notificationsOn) {
            Text(
                "Notifications are off for Planner, so none of these will show. Planning, ticking and the calendar still work.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Button(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                }
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Notification settings") }
            }
        }
        if (!exactAlarms) {
            Text(
                "Exact alarms are off, so timed reminders can arrive a few minutes late. " +
                    "To fix it, open Alarms & reminders for Planner and allow setting alarms and reminders.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Allow exact alarms") }
        }
        if (backgroundSleep == false) {
            Text(
                "Health Connect hasn't allowed Planner to read sleep while the app is closed, so the back-to-bed notice " +
                    "and the morning brief can only use sleep from the last time you opened the app.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { healthBackground.launch(setOf(SleepRepository.BACKGROUND_PERMISSION)) }) {
                Text("Allow background sleep reading")
            }
        }
        Routine.entries.forEach { routine ->
            val info = routine.info
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(info.label, style = MaterialTheme.typography.bodyLarge)
                    Text(info.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled[routine] != false, onCheckedChange = { vm.setEnabled(routine, it) })
            }
        }
    }
}
