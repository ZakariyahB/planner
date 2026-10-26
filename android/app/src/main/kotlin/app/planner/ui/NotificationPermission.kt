package app.planner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Android 13+ asks before an app may post notifications. This explains why first, once; declining is fine,
 * the planner keeps working and Settings → Routines can ask again.
 */
@Composable
fun NotificationPermissionPrompt(vm: RoutinePrefsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val seen by vm.promptSeen.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.markPromptSeen() }

    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    if (!needsPermission || seen != false) return

    AlertDialog(
        onDismissRequest = vm::markPromptSeen,
        title = { Text("Allow notifications?") },
        text = {
            Text(
                "Planner can tell you when work stops, send tomorrow's plan at 23:30 for you to approve, " +
                    "warn you before lights-out and suggest going back to bed after an early wake-up. " +
                    "You can switch each one off in Settings, and the planner works without them.",
            )
        },
        confirmButton = { TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = vm::markPromptSeen) { Text("Not now") } },
    )
}
