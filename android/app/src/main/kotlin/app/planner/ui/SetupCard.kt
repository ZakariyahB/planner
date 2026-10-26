package app.planner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.planner.setup.SetupChecklist
import app.planner.setup.SetupItem
import app.planner.setup.SetupStep
import app.planner.ui.theme.LocalStatusColors
import java.time.LocalTime

/**
 * "Get set up": what the app still needs from the user, each with a button to do it. Shown on Today until
 * everything is done; the caller hides it then and re-checks whenever the app comes back to the front.
 */
@Composable
fun SetupCard(
    items: List<SetupItem>,
    note: String?,
    vm: SetupViewModel,
    onConnect: () -> Unit,
    onAllowHealth: () -> Unit,
    onSetFajr: (LocalTime) -> Unit,
) {
    val context = LocalContext.current
    var pickingFajr by remember { mutableStateOf(false) }

    // If Android won't ask (it was refused before), the app's notification settings are the way to switch them on.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.openNotificationSettings()
        vm.refresh()
    }
    val act: (SetupStep) -> Unit = { step ->
        when (step) {
            SetupStep.CALENDAR -> onConnect()
            SetupStep.HEALTH -> onAllowHealth()
            SetupStep.FAJR -> pickingFajr = true
            SetupStep.NOTIFICATIONS -> {
                val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsRuntimePermission) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.openNotificationSettings()
            }
            SetupStep.EXACT_ALARMS -> vm.openExactAlarmSettings()
            SetupStep.USAGE_ACCESS -> vm.openUsageAccess()
        }
    }
    val next = SetupChecklist.next(items)

    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Get set up", style = MaterialTheme.typography.titleMedium)
            Text(SetupChecklist.summary(items), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { SetupChecklist.progress(items).toFloat() }, modifier = Modifier.fillMaxWidth())
        items.forEach { item -> SetupRow(item, isNext = item.step == next, onAction = { act(item.step) }) }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalStatusColors.current.warningText) }
    }

    if (pickingFajr) {
        TimePickerDialog(
            title = "Fajr this week",
            initial = LocalTime.of(5, 30),
            onConfirm = { onSetFajr(it); pickingFajr = false },
            onDismiss = { pickingFajr = false },
        )
    }
}

@Composable
private fun SetupRow(item: SetupItem, isNext: Boolean, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (item.done) "✓" else "○",
            Modifier.width(20.dp),
            color = if (item.done) LocalStatusColors.current.good else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
        Column(Modifier.weight(1f)) {
            Text(item.step.title, style = MaterialTheme.typography.bodyLarge, color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (!item.done) Text(item.step.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!item.done) {
            if (isNext) Button(onClick = onAction, modifier = Modifier.heightIn(min = 44.dp)) { Text(item.step.action) }
            else OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 44.dp)) { Text(item.step.action) }
        }
    }
}
