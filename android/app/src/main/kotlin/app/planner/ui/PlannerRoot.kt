package app.planner.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import kotlinx.coroutines.flow.collectLatest

private enum class Tab(val label: String) { TODAY("Today"), CALENDAR("Calendar"), TASKS("Tasks"), ASK("Ask"), INSIGHTS("Insights") }

/** Where the user is: one of the five tabs, or a page of Settings. */
private sealed interface Destination {
    data class Main(val tab: Tab) : Destination

    data class Settings(val page: SettingsPage) : Destination
}

/** How far into the app a destination is: tabs are level 0, the Settings list 1, a Settings page 2. */
private fun Destination.depth(): Int = when (this) {
    is Destination.Main -> 0
    is Destination.Settings -> if (page == SettingsPage.HOME) 1 else 2
}

/** Tabs fade through each other; going deeper slides in from the right and going back slides in from the left. */
private fun destinationTransition(from: Destination, to: Destination): ContentTransform {
    val direction = to.depth().compareTo(from.depth())
    if (direction == 0) {
        return ContentTransform(
            targetContentEnter = fadeIn(tween(NAV_FADE_IN_MS, delayMillis = NAV_FADE_OUT_MS)),
            initialContentExit = fadeOut(tween(NAV_FADE_OUT_MS)),
            sizeTransform = NO_SIZE_CHANGE,
        )
    }
    val enter = slideInHorizontally(tween(NAV_SLIDE_MS, easing = FastOutSlowInEasing)) { direction * it / NAV_SLIDE_FRACTION } +
        fadeIn(tween(NAV_FADE_IN_MS))
    val exit = slideOutHorizontally(tween(NAV_SLIDE_MS, easing = FastOutSlowInEasing)) { -direction * it / NAV_SLIDE_FRACTION } +
        fadeOut(tween(NAV_FADE_OUT_MS))
    return ContentTransform(targetContentEnter = enter, initialContentExit = exit, sizeTransform = NO_SIZE_CHANGE)
}

/** The screens always fill the same space, so the container never needs to animate its size. */
private val NO_SIZE_CHANGE = SizeTransform(clip = false) { _, _ -> snap() }

private const val NAV_FADE_OUT_MS = 90
private const val NAV_FADE_IN_MS = 180
private const val NAV_SLIDE_MS = 260
private const val NAV_SLIDE_FRACTION = 8

/**
 * Shows [text] briefly, with Undo when [undoChangeId] is set. A snackbar with an action never times out unless it
 * is given a duration, so one is always set; every change can also be undone later from Change history.
 */
private suspend fun SnackbarHostState.announce(text: String, undoChangeId: Long?, undo: (Long) -> Unit) {
    val result = showSnackbar(text, actionLabel = if (undoChangeId != null) "Undo" else null, duration = SnackbarDuration.Short)
    if (result == SnackbarResult.ActionPerformed) undoChangeId?.let(undo)
}

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
    val insightsVm: InsightsViewModel = hiltViewModel()
    SocialEffects()

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var progressFor by remember { mutableStateOf<Task?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // Google's consent screen opens only after the user taps Connect, never on a silent background sync.
    var userConnecting by remember { mutableStateOf(false) }
    val sync by calVm.sync.collectAsStateWithLifecycle()
    val lastSynced by calVm.lastSyncedAt.collectAsStateWithLifecycle()
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
        planVm.messages.collectLatest { m -> snackbar.announce(m.text, m.undoChangeId, planVm::undo) }
    }

    // Changes the assistant made for the user get the same Undo snackbar.
    LaunchedEffect(Unit) {
        chatVm.announcements.collectLatest { m -> snackbar.announce(m.text, m.undoChangeId, planVm::undo) }
    }

    val openTemplate = { showSettings = true; settingsPage = SettingsPage.TEMPLATE }

    // Back steps up one level: a settings page to the list, the list to wherever you were.
    val goBack = { if (settingsPage != SettingsPage.HOME) settingsPage = SettingsPage.HOME else showSettings = false }
    BackHandler(enabled = showSettings) { goBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (showSettings) settingsPage.title else "Planner") },
                navigationIcon = { if (showSettings) TextButton(onClick = { goBack() }) { Text("Back") } },
                actions = { if (!showSettings) TextButton(onClick = { settingsPage = SettingsPage.HOME; showSettings = true }) { Text("Settings") } },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = !showSettings && tab == t,
                        onClick = { tab = t; showSettings = false; settingsPage = SettingsPage.HOME },
                        icon = {},
                        label = { Text(t.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { padding ->
        val destination = if (showSettings) Destination.Settings(settingsPage) else Destination.Main(tab)
        AnimatedContent(
            targetState = destination,
            modifier = Modifier.padding(padding),
            transitionSpec = { destinationTransition(initialState, targetState) },
            label = "destination",
        ) { dest ->
            when (dest) {
                is Destination.Settings -> SettingsScreen(
                    page = dest.page,
                    onNavigate = { settingsPage = it },
                    calVm = calVm, readinessVm = readinessVm, planVm = planVm, chatVm = chatVm, tasksVm = tasksVm,
                    onConnect = onConnect, onAllowHealth = onAllowHealth,
                )
                is Destination.Main -> when (dest.tab) {
                    Tab.TODAY -> TodayScreen(
                        tasksVm, calVm, planVm, readinessVm, onConnect, onAllowHealth,
                        onEdit = { editor = EditorTarget(it) }, onAdd = { editor = EditorTarget(null) },
                        onLogProgress = { progressFor = it },
                        onOpenTemplate = openTemplate,
                    )
                    Tab.CALENDAR -> CalendarScreen(planVm, calendarConnected = lastSynced != null, onEditTask = { editor = EditorTarget(it) }, onLogProgress = { progressFor = it })
                    Tab.TASKS -> TasksScreen(tasksVm, planVm, onEdit = { editor = EditorTarget(it) }, onAdd = { editor = EditorTarget(null) }, onLogProgress = { progressFor = it }, onOpenTemplate = openTemplate)
                    Tab.ASK -> ChatScreen(chatVm)
                    Tab.INSIGHTS -> InsightsScreen(insightsVm)
                }
            }
        }
    }

    NotificationPermissionPrompt()
    RoutineScreens(
        onAsk = { draft -> chatVm.setDraft(draft); tab = Tab.ASK; showSettings = false },
        onOpenTemplate = openTemplate,
    )

    progressFor?.let { task ->
        ProgressSheet(
            task = task,
            onSave = { minutes, outcome -> tasksVm.logProgress(task, minutes, outcome); progressFor = null },
            onDismiss = { progressFor = null },
        )
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
