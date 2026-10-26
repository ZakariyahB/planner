package app.planner.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.model.Task
import app.planner.sleep.SleepRepository
import app.planner.social.SocialEffects

private enum class Tab(val label: String) { TODAY("Today"), CALENDAR("Calendar"), TASKS("Tasks"), ASK("Ask") }

/** Null [task] means "add a new one". */
private data class EditorTarget(val task: Task?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerRoot() {
    val tasksVm: TasksViewModel = hiltViewModel()
    val calVm: CalendarViewModel = hiltViewModel()
    val planVm: PlanViewModel = hiltViewModel()
    val readinessVm: ReadinessViewModel = hiltViewModel()
    val chatVm: ChatViewModel = hiltViewModel()
    SocialEffects()

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showTemplate by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // Google's consent screen opens only after the user taps Connect, never on a silent background sync.
    var userConnecting by remember { mutableStateOf(false) }
    val sync by calVm.sync.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { calVm.onConsentResult(it.data) }
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

    // Health Connect has its own permission screen; when it closes, read sleep again.
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { readinessVm.syncSleep() }
    val onAllowHealth = { health.launch(SleepRepository.PERMISSIONS) }

    // Plan feedback, with Undo when the change can be reverted.
    LaunchedEffect(Unit) {
        planVm.messages.collect { m ->
            val result = snackbar.showSnackbar(m.text, actionLabel = if (m.undoChangeId != null) "Undo" else null)
            if (result == SnackbarResult.ActionPerformed) m.undoChangeId?.let(planVm::undo)
        }
    }

    // Changes the assistant made for the user get the same Undo snackbar.
    LaunchedEffect(Unit) {
        chatVm.announcements.collect { m ->
            val result = snackbar.showSnackbar(m.text, actionLabel = if (m.undoChangeId != null) "Undo" else null)
            if (result == SnackbarResult.ActionPerformed) m.undoChangeId?.let(planVm::undo)
        }
    }

    BackHandler(enabled = showSettings) {
        if (showTemplate) showTemplate = false else showSettings = false
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (showSettings) "Settings" else "Planner") },
                navigationIcon = { if (showSettings) TextButton(onClick = { if (showTemplate) showTemplate = false else showSettings = false }) { Text("Back") } },
                actions = { if (!showSettings) TextButton(onClick = { showSettings = true }) { Text("Settings") } },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = !showSettings && tab == t,
                        onClick = { tab = t; showSettings = false; showTemplate = false },
                        icon = {},
                        label = { Text(t.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (showSettings) {
                if (showTemplate) TemplateScreen(tasksVm, onBack = { showTemplate = false })
                else SettingsScreen(calVm, readinessVm, planVm, chatVm, onConnect, onAllowHealth, onOpenTemplate = { showTemplate = true })
            } else when (tab) {
                Tab.TODAY -> TodayScreen(
                    tasksVm, calVm, planVm, readinessVm, onConnect, onAllowHealth,
                    onEdit = { editor = EditorTarget(it) }, onAdd = { editor = EditorTarget(null) },
                )
                Tab.CALENDAR -> CalendarScreen(planVm)
                Tab.TASKS -> TasksScreen(tasksVm, planVm, onEdit = { editor = EditorTarget(it) }, onAdd = { editor = EditorTarget(null) })
                Tab.ASK -> ChatScreen(chatVm)
            }
        }
    }

    editor?.let { target ->
        TaskEditor(
            task = target.task,
            onSave = { tasksVm.save(it); editor = null },
            onSaveOnDays = { t, dates -> tasksVm.saveOnDates(t, dates); editor = null },
            onDrop = target.task?.let { t -> { tasksVm.drop(t); editor = null } },
            onDismiss = { editor = null },
        )
    }
}
