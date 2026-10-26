package app.planner.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.ui.theme.LocalStatusColors

/** Keeps social time up to date while the app is open. Placed once in the app root; the screens share its view model. */
@Composable
fun SocialEffects() {
    val vm: SocialViewModel = hiltViewModel()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
}

/** The social-time entry on Today: how to turn on usage access, or (once on) the meter. */
@Composable
fun SocialTimeCard(vm: SocialViewModel = hiltViewModel()) {
    val granted by vm.accessGranted.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val meter by vm.meter.collectAsStateWithLifecycle()
    if (!granted) UsageAccessCard(note, onOpen = vm::openAccessSettings) else meter?.let { SocialMeterRow(it) }
}

@Composable
private fun SocialMeterRow(meter: SocialMeter) {
    val color = if (meter.over) LocalStatusColors.current.warning else MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Social time", style = MaterialTheme.typography.titleMedium)
            Text("${SocialNudges.duration(meter.usedMin)} of ${SocialNudges.duration(meter.budgetMin)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { meter.fraction }, color = color, modifier = Modifier.fillMaxWidth())
        Text(
            when {
                meter.noApps -> "No social apps are selected. Choose them in Settings."
                meter.over -> "${meter.usedMin - meter.budgetMin} minutes over budget. Just a nudge, nothing is blocked."
                else -> "${meter.budgetMin - meter.usedMin} minutes left today"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (meter.over) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsageAccessCard(note: String?, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Text("Turn on usage access for social time", style = MaterialTheme.typography.titleMedium)
        Text(
            "Planner counts how long your social apps are open so it can nudge you before you pass your daily budget. " +
                "Android has no pop-up for this: tap the button, choose Planner, and switch on \"Allow usage access\". " +
                "Planner only reads how long the apps you pick were open and keeps the minutes on this phone. Nothing is ever blocked.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (note != null) Text(note, color = MaterialTheme.colorScheme.error)
        Button(onClick = onOpen) { Text("Open usage access settings") }
    }
}
