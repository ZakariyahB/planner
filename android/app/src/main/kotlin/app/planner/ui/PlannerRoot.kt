package app.planner.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.sleep.SleepRepository
import app.planner.social.SocialEffects
import app.planner.ui.approval.ApprovalEvent
import app.planner.ui.approval.ApprovalHost
import app.planner.ui.approval.ApprovalViewModel
import app.planner.ui.ask.AskRoute
import app.planner.ui.ask.AskViewModel
import app.planner.ui.backlog.BacklogRoute
import app.planner.ui.backlog.BacklogViewModel
import app.planner.ui.model.LaunchRequest
import app.planner.ui.rhythm.FloatingNav
import app.planner.ui.rhythm.NavItem
import app.planner.ui.settings.PlannerSettingsRoute
import app.planner.ui.settings.PlannerSettingsViewModel
import app.planner.ui.settings.SettingsActions
import app.planner.ui.settings.SignInEvent
import app.planner.ui.settings.SignInViewModel
import app.planner.ui.shell.UiLaunch
import app.planner.ui.theme.RhythmTheme
import app.planner.ui.today.TodayRoute
import app.planner.ui.today.TodayViewModel
import app.planner.ui.week.WeekRoute
import app.planner.ui.week.WeekViewModel
import app.planner.checkin.CheckInSetupCard
import kotlinx.coroutines.flow.collectLatest

// The v2 shell: four tabs in a floating pill (Today, Week, Backlog, Ask), Settings and Reflect over them, and the
// approval sheet / screen over everything. Approval opens from the Today banner, any "Review" button, a proposal from
// Ask, and the "Review change" notification (intent extra open=approvals, with changeSetId).

private enum class Tab(val label: String) { TODAY("Today"), WEEK("Week"), BACKLOG("Backlog"), ASK("Ask") }

/** Screens drawn over the tabs. Stored as a string so it survives rotation. "legacy:PAGE" is an older settings page. */
private object Overlay {
    const val SETTINGS = "settings"
    const val REFLECT = "reflect"
    const val LEGACY = "legacy:"
}

private val NavClearance = 96.dp

@Composable
fun PlannerRoot() {
    val todayVm: TodayViewModel = hiltViewModel()
    val weekVm: WeekViewModel = hiltViewModel()
    val backlogVm: BacklogViewModel = hiltViewModel()
    val askVm: AskViewModel = hiltViewModel()
    val approvalVm: ApprovalViewModel = hiltViewModel()
    val signInVm: SignInViewModel = hiltViewModel()
    val appearanceVm: AppearanceViewModel = hiltViewModel()
    SocialEffects()

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    var approvalOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val tabState = rememberSaveableStateHolder()

    val today by todayVm.state.collectAsStateWithLifecycle()
    val pending = today?.pendingCount ?: 0

    val openApproval: (String?) -> Unit = { id ->
        approvalVm.open(id)
        approvalOpen = true
    }

    // Google's consent screen opens only after the user taps Sign in.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { signInVm.onConsentResult(it.data) }
    LaunchedEffect(signInVm) {
        signInVm.messages.collect { e ->
            when (e) {
                is SignInEvent.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(e.sender).build())
                is SignInEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }
    val signIn = { signInVm.signIn(); Unit }

    // Health Connect has its own permission screen (used by the older Sleep page).
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { }

    // After an approval: what was written, with Undo.
    LaunchedEffect(approvalVm) {
        approvalVm.messages.collectLatest { e ->
            when (e) {
                is ApprovalEvent.Applied -> {
                    val r = snackbar.showSnackbar(e.text, actionLabel = if (e.record != null) "Undo" else null, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) e.record?.let { approvalVm.undo(it) }
                }
                is ApprovalEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }

    // Notifications ask for a destination through the intent; MainActivity hands it to UiLaunch.
    val launch by UiLaunch.requests.collectAsStateWithLifecycle()
    LaunchedEffect(launch) {
        when (val r = launch) {
            null -> return@LaunchedEffect
            is LaunchRequest.Approvals -> { overlay = null; openApproval(r.changeSetId) }
            LaunchRequest.Today -> { overlay = null; tab = Tab.TODAY }
            LaunchRequest.Week -> { overlay = null; tab = Tab.WEEK }
            LaunchRequest.Chat -> { overlay = null; tab = Tab.ASK }
            LaunchRequest.Settings -> overlay = Overlay.SETTINGS
        }
        UiLaunch.consume()
    }

    BackHandler(enabled = overlay != null) {
        overlay = when {
            overlay?.startsWith(Overlay.LEGACY) == true -> Overlay.SETTINGS
            else -> null
        }
    }
    BackHandler(enabled = overlay == null && tab != Tab.TODAY && !approvalOpen) { tab = Tab.TODAY }

    Surface(Modifier.fillMaxSize(), color = RhythmTheme.colors.ground) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(90)) },
                    label = "tab",
                ) { t ->
                    tabState.SaveableStateProvider(t.name) {
                        when (t) {
                            Tab.TODAY -> TodayRoute(
                                todayVm, snackbar,
                                onOpenApproval = openApproval,
                                onOpenSettings = { overlay = Overlay.SETTINGS },
                                onSignIn = signIn,
                                bottomPadding = NavClearance,
                                setupCard = { CheckInSetupCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) },
                            )
                            Tab.WEEK -> WeekRoute(weekVm, snackbar, openApproval, onOpenReflect = { overlay = Overlay.REFLECT }, bottomPadding = NavClearance)
                            Tab.BACKLOG -> BacklogRoute(backlogVm, snackbar, openApproval, bottomPadding = NavClearance)
                            Tab.ASK -> AskRoute(askVm, openApproval, bottomPadding = NavClearance)
                        }
                    }
                }
            }

            FloatingNav(
                items = Tab.entries.map { NavItem(it.name, it.label, badge = if (it == Tab.ASK) pending else 0) },
                selected = tab.name,
                onSelect = { key -> tab = Tab.valueOf(key) },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
            )

            val o = overlay
            when {
                o == Overlay.SETTINGS -> Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    val mode by appearanceVm.mode.collectAsStateWithLifecycle()
                    val settingsVm: PlannerSettingsViewModel = hiltViewModel()
                    PlannerSettingsRoute(
                        settingsVm, mode,
                        SettingsActions(
                            onUpdate = { f -> settingsVm.update(f) },
                            onSignIn = signIn,
                            onRefresh = { settingsVm.refresh() },
                            onTheme = appearanceVm::choose,
                            onOpenReflect = { overlay = Overlay.REFLECT },
                            onOpenLegacy = { page -> overlay = Overlay.LEGACY + page.name },
                            onBack = { overlay = null },
                        ),
                    )
                }
                o == Overlay.REFLECT -> OverPage("Reflect", onBack = { overlay = null }) { InsightsScreen(hiltViewModel()) }
                o != null && o.startsWith(Overlay.LEGACY) -> {
                    val page = SettingsPage.entries.firstOrNull { it.name == o.removePrefix(Overlay.LEGACY) } ?: SettingsPage.HOME
                    OverPage(page.title, onBack = { overlay = Overlay.SETTINGS }) {
                        SettingsScreen(
                            page = page,
                            onNavigate = { p -> overlay = Overlay.LEGACY + p.name },
                            calVm = hiltViewModel(), readinessVm = hiltViewModel(), planVm = hiltViewModel(),
                            chatVm = hiltViewModel(), tasksVm = hiltViewModel(),
                            onConnect = signIn,
                            onAllowHealth = { health.launch(SleepRepository.PERMISSIONS) },
                        )
                    }
                }
            }

            if (approvalOpen) {
                ApprovalHost(approvalVm, onClose = { approvalOpen = false })
            }

            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = if (overlay == null) 86.dp else 12.dp),
            )
        }
    }

    RoutineScreens(
        onAsk = { draft -> askVm.setDraft(draft); overlay = null; tab = Tab.ASK },
        onOpenTemplate = { overlay = Overlay.LEGACY + SettingsPage.TEMPLATE.name },
    )
}

/** A full-screen page over the tabs with a back arrow, for Reflect and the older settings pages. */
@Composable
private fun OverPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = RhythmTheme.colors.ground) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = RhythmTheme.colors.ink) }
                Text(title, style = MaterialTheme.typography.headlineSmall)
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
