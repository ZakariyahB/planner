package app.planner.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.ui.theme.LocalStatusColors

/**
 * The 23:30 sheet (docs/PLAN.md "Plan"): tomorrow's blocks with an include switch each, Approve and sleep,
 * and Ask Claude, which opens the coach sheet with a draft about tomorrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TomorrowPlanSheet(onDismiss: () -> Unit, onAsk: (String) -> Unit, vm: TomorrowPlanViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.load() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("Tomorrow, ${dayHeaderFormat.format(ui.date)}", style = MaterialTheme.typography.headlineSmall)
            }
            ui.forecast?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            ui.note?.let { item { Text(it) } }

            if (ui.loaded && ui.rows.isEmpty()) {
                item { Text("Nothing is planned for tomorrow.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(ui.rows, key = { it.taskId }) { row ->
                val included = row.taskId !in ui.excluded
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggle(row.taskId) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull("${timeFormat.format(row.start)}–${timeFormat.format(row.end)}", row.module).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!included) {
                            Text(
                                if (row.dueByTomorrow) "Due tomorrow: leaving it out makes it late" else "Moves to the day after tomorrow",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (row.dueByTomorrow) LocalStatusColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(checked = included, onCheckedChange = { vm.toggle(row.taskId) })
                }
            }
            item {
                Row(Modifier.padding(top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.approve { deferred ->
                            Toast.makeText(
                                context,
                                if (deferred == 0) "Plan approved. Sleep well." else "Approved: $deferred task${if (deferred == 1) "" else "s"} moved on. Sleep well.",
                                Toast.LENGTH_SHORT,
                            ).show()
                            onDismiss()
                        }
                    }) { Text("Approve and sleep") }
                    OutlinedButton(onClick = { onAsk(vm.askDraft()) }) { Text("Ask Claude") }
                }
            }
        }
    }
}
