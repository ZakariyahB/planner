package app.planner.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.model.Task

private enum class Tab(val label: String) { TODAY("Today"), TASKS("Tasks"), SETTINGS("Settings") }

/** Null [task] means "add a new one". */
private data class EditorTarget(val task: Task?)

@Composable
fun PlannerRoot() {
    val tasksVm: TasksViewModel = hiltViewModel()
    val calVm: CalendarViewModel = hiltViewModel()

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var showTemplate by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }

    // Google's consent screen opens only after the user taps Connect, never on a silent background sync.
    var userConnecting by remember { mutableStateOf(false) }
    val sync by calVm.sync.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { calVm.refresh() }
    LaunchedEffect(sync, userConnecting) {
        val s = sync
        when {
            s is SyncUi.NeedsConsent && userConnecting -> {
                userConnecting = false
                consent.launch(IntentSenderRequest.Builder(s.sender).build())
            }
            s !is SyncUi.Syncing && s !is SyncUi.NeedsConsent -> userConnecting = false
        }
    }
    val onConnect = {
        userConnecting = true
        calVm.refresh()
        Unit
    }

    BackHandler(enabled = showTemplate) { showTemplate = false }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t; showTemplate = false }, icon = {}, label = { Text(t.label) }, alwaysShowLabel = true)
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        Box(modifier) {
            when {
                tab == Tab.TODAY -> TodayScreen(tasksVm, calVm, onConnect, onEdit = { editor = EditorTarget(it) }, onAdd = { editor = EditorTarget(null) })
                tab == Tab.TASKS -> TasksScreen(tasksVm, onEdit = { editor = EditorTarget(it) }, onAdd = { editor = EditorTarget(null) })
                showTemplate -> TemplateScreen(tasksVm, onBack = { showTemplate = false })
                else -> SettingsScreen(calVm, onConnect, onOpenTemplate = { showTemplate = true })
            }
        }
    }

    editor?.let { target ->
        TaskEditor(
            task = target.task,
            onSave = { tasksVm.save(it); editor = null },
            onDrop = target.task?.let { t -> { tasksVm.drop(t); editor = null } },
            onDismiss = { editor = null },
        )
    }
}
