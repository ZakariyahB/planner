package app.planner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.backup.BackupFile
import app.planner.ui.theme.LocalStatusColors
import java.time.ZoneId

@Composable
fun BackupScreen(vm: BackupViewModel) {
    val step by vm.step.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val includeChat by vm.includeChat.collectAsStateWithLifecycle()

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { vm.save(it) }
    // Any file type is offered: backups saved to some cloud drives lose their type, and a wrong file is refused with a reason.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.choose(it) }
    val busy = step is BackupStep.Working

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Box(Modifier.height(4.dp)) }
        item {
            Text(
                "Everything stays on this phone, so a copy in a file is the only protection against losing it. Keep one somewhere safe, such as Google Drive.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let { m -> item { Text(m.text, color = if (m.good) LocalStatusColors.current.good else MaterialTheme.colorScheme.error) } }
        (step as? BackupStep.Working)?.let { item { Text(it.what, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

        item {
            BackupSection("Save a copy") {
                Text(
                    "Your tasks, plan, sleep, social time, change history and settings, in one file.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Include the assistant conversation", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Off by default. Your messages to the assistant stay out of the file unless you turn this on.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = includeChat, onCheckedChange = vm::setIncludeChat)
                }
                Button(onClick = { saver.launch(vm.suggestedName()) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Save to a file")
                }
            }
        }

        item {
            BackupSection("Restore from a file") {
                Text(
                    "Choose a backup you saved earlier. You will see what is in it before anything changes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Choose a file")
                }
            }
        }

        (step as? BackupStep.Confirm)?.let { confirm ->
            item { ConfirmRestore(confirm.file, onCancel = vm::cancel, onReplace = vm::restore) }
        }
        item { Box(Modifier.height(8.dp)) }
    }
}

@Composable
private fun BackupSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun ConfirmRestore(file: BackupFile, onCancel: () -> Unit, onReplace: () -> Unit) {
    val made = file.createdAt.atZone(ZoneId.systemDefault())
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Replace what is on this phone?", style = MaterialTheme.typography.titleMedium)
        Text("Backup made ${dateFormat.format(made)} at ${timeFormat.format(made)}", style = MaterialTheme.typography.bodyLarge)
        Text(file.describe().ifEmpty { "No records" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Restoring replaces what is on the phone with what is in this file. Anything you have added or changed since " +
                "then is lost, and this cannot be undone.",
            color = LocalStatusColors.current.warningText,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            Button(onClick = onReplace, modifier = Modifier.heightIn(min = 48.dp)) { Text("Replace") }
        }
    }
}
