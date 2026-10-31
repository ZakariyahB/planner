package app.planner.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.ports.AuthState
import app.planner.engine.ports.TodayBlock
import app.planner.ui.model.BlockTone
import app.planner.ui.model.Fmt
import app.planner.ui.model.PrayerMarker
import app.planner.ui.model.Stretch
import app.planner.ui.model.TimelineBlock
import app.planner.ui.model.TimelineItem
import app.planner.ui.rhythm.Banner
import app.planner.ui.rhythm.BannerTone
import app.planner.ui.rhythm.EmptyNote
import app.planner.ui.rhythm.FlagChip
import app.planner.ui.rhythm.GroupLabel
import app.planner.ui.rhythm.PillButton
import app.planner.ui.rhythm.PillKind
import app.planner.ui.rhythm.QuietButton
import app.planner.ui.rhythm.RhythmCard
import app.planner.ui.rhythm.ScreenHeader
import app.planner.ui.shell.openGoogleCalendar
import app.planner.ui.theme.MarkerStyle
import app.planner.ui.theme.RhythmTheme
import java.time.LocalDateTime

/** What the Today screen can ask for. The view model does the work; the composables only call these. */
class TodayActions(
    val onStart: (TodayBlock) -> Unit = {},
    val onDone: (TodayBlock) -> Unit = {},
    val onNeedMore: (TodayBlock) -> Unit = {},
    val onStopHere: (TodayBlock) -> Unit = {},
    val onSkip: (TodayBlock) -> Unit = {},
    val onFocus: (Int?) -> Unit = {},
    val onPlanToday: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpenApprovals: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onOpenCalendar: (LocalDateTime) -> Unit = {},
)

@Composable
fun TodayRoute(
    vm: TodayViewModel,
    snackbar: SnackbarHostState,
    onOpenApproval: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    setupCard: @Composable () -> Unit = {},
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.messages.collect { e ->
            when (e) {
                is TodayEvent.OpenApproval -> onOpenApproval(e.changeSetId)
                is TodayEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }
    val state = ui
    if (state == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RhythmTheme.colors.accent) }
        return
    }
    TodayContent(
        ui = state,
        bottomPadding = bottomPadding,
        setupCard = setupCard,
        actions = TodayActions(
            onStart = { vm.start(it) },
            onDone = { vm.done(it) },
            onNeedMore = { vm.needMore(it) },
            onStopHere = { vm.stopHere(it) },
            onSkip = { vm.skip(it) },
            onFocus = { vm.focus(it) },
            onPlanToday = { vm.planToday() },
            onRefresh = { vm.refresh() },
            onOpenApprovals = { onOpenApproval(null) },
            onOpenSettings = onOpenSettings,
            onSignIn = onSignIn,
            onOpenCalendar = { at -> openGoogleCalendar(context, at, state.settings.zone) },
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayContent(
    ui: TodayUi,
    actions: TodayActions,
    bottomPadding: androidx.compose.ui.unit.Dp = 96.dp,
    setupCard: @Composable () -> Unit = {},
) {
    val c = RhythmTheme.colors
    val t = ui.timeline
    var sheetFor by remember { mutableStateOf<TimelineBlock?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "header") {
            ScreenHeader(eyebrow = t.dateLabel, title = t.heading, subtitle = t.subheading) {
                IconButton(onClick = actions.onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = c.ink) }
            }
        }
        item(key = "setup") { setupCard() }
        if (ui.auth != AuthState.SIGNED_IN) {
            item(key = "auth") {
                val expired = ui.auth == AuthState.EXPIRED
                Banner(
                    text = if (expired) "Google sign-in expired" else "Google Calendar is not connected",
                    detail = if (expired) "Nothing is written to your calendar until you sign in again." else "Connect it to see your blocks and approve changes.",
                    actionLabel = if (expired) "Sign in again" else "Connect",
                    onAction = actions.onSignIn,
                    tone = BannerTone.WARNING,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
        if (ui.pendingCount > 0) {
            item(key = "pending") {
                Banner(
                    text = if (ui.pendingCount == 1) "1 change waiting for you" else "${ui.pendingCount} changes waiting for you",
                    detail = "Nothing changes in Google Calendar until you approve.",
                    actionLabel = "Review",
                    onAction = actions.onOpenApprovals,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
        if (ui.dayFlags.isNotEmpty()) {
            item(key = "flags") {
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.dayFlags.take(2).forEach { FlagChip(it, warning = it.startsWith("Extended") || it.startsWith("Over")) }
                }
            }
        }
        if (t.isEmpty) {
            item(key = "empty") {
                EmptyNote(
                    "Nothing on the calendar today",
                    if (ui.unscheduled.isNotEmpty()) "Your template has items for today. Plan them below." else "Add a task in Backlog, or ask for one.",
                    Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(t.items, key = { it.key }) { item ->
            when (item) {
                is TimelineItem.Marker -> MarkerRow(item.marker)
                is TimelineItem.Section -> StretchSection(item.stretch, actions, onOpenBlock = { sheetFor = it })
            }
        }
        if (ui.unscheduled.isNotEmpty()) {
            item(key = "unscheduled-label") { GroupLabel("Not planned yet", Modifier.padding(top = 16.dp)) }
            items(ui.unscheduled, key = { "u-" + it.task.id }) { u ->
                RhythmCard(Modifier.padding(vertical = 4.dp)) {
                    Text(u.title, style = MaterialTheme.typography.titleMedium)
                    Text(u.detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
            item(key = "plan-today") {
                PillButton("Plan today", actions.onPlanToday, Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        item(key = "footer") {
            Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton(
                    "Open in Google Calendar",
                    { actions.onOpenCalendar(t.now?.block?.start ?: t.next?.block?.start ?: t.date.atTime(9, 0)) },
                    Modifier.fillMaxWidth(),
                    kind = PillKind.SECONDARY,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ui.syncLine ?: "Not synced yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.syncFailed) c.amberText else c.muted,
                        modifier = Modifier.weight(1f),
                    )
                    if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
                    else IconButton(onClick = actions.onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Sync with Google now", tint = c.muted) }
                }
            }
        }
    }

    sheetFor?.let { b ->
        BlockSheet(b, actions, onDismiss = { sheetFor = null })
    }
    ui.focusFor?.let { log ->
        FocusSheet(log.title, onPick = actions.onFocus)
    }
}

@Composable
private fun MarkerRow(m: PrayerMarker) {
    val c = RhythmTheme.colors
    val ring = if (m.next) c.accent else c.ink
    val spoken = buildString {
        append(Fmt.prayer(m.prayer)).append(" at ").append(m.timeLabel.removePrefix("~"))
        if (m.interpolated) append(", estimated")
        m.note?.let { append(", ").append(it) }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = spoken; heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(12.dp).background(c.ground, CircleShape).border(2.dp, ring, CircleShape))
        Text(Fmt.prayer(m.prayer), style = MarkerStyle, color = c.ink)
        Text(listOfNotNull(m.timeLabel, m.note).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

@Composable
private fun StretchSection(s: Stretch, actions: TodayActions, onOpenBlock: (TimelineBlock) -> Unit) {
    val c = RhythmTheme.colors
    val lineColor = when {
        s.extended -> c.amberLine
        s.current -> c.accent
        else -> c.line
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics { contentDescription = s.title }) {
        Box(
            Modifier
                .padding(start = 5.dp)
                .width(2.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    drawLine(
                        color = lineColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = size.width,
                        pathEffect = if (s.extended) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null,
                    )
                },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
            s.blocks.forEach { b ->
                if (b.isNow) NowCard(b, actions, onMore = { onOpenBlock(b) })
                else BlockRow(b, onClick = { onOpenBlock(b) })
            }
        }
    }
}

@Composable
private fun NowCard(b: TimelineBlock, actions: TodayActions, onMore: () -> Unit) {
    val c = RhythmTheme.colors
    Surface(shape = RoundedCornerShape(20.dp), color = c.nowCard, contentColor = c.onNowCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Now · ${b.block.calendarName}", style = MaterialTheme.typography.labelMedium, color = c.onNowCardMuted)
                Text(b.rangeLabel, style = MaterialTheme.typography.labelMedium, color = c.onNowCardMuted)
            }
            Text(b.title, style = MaterialTheme.typography.headlineSmall, color = c.onNowCard, modifier = Modifier.semantics { heading() })
            Text(b.workLabel, style = MaterialTheme.typography.bodySmall, color = c.onNowCardMuted)
            if (b.block.isFixed) {
                QuietButton("Details", onMore, color = c.onNowCard)
            } else if (b.isDone) {
                Text("Logged as done. Nice.", style = MaterialTheme.typography.bodyMedium, color = c.onNowCard)
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Done", { actions.onDone(b.block) }, Modifier.weight(1f), kind = PillKind.ON_ACCENT, contentDescription = "Done with ${b.title}")
                    PillButton("Need 30 more", { actions.onNeedMore(b.block) }, Modifier.weight(1f), kind = PillKind.ON_ACCENT_OUTLINE, contentDescription = "Need 30 more minutes on ${b.title}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (b.outcome == ExecutionOutcome.PENDING) QuietButton("Log start", { actions.onStart(b.block) }, color = c.onNowCard)
                    else b.log?.actualStart?.let { Text("Started ${Fmt.time(it)}", style = MaterialTheme.typography.bodySmall, color = c.onNowCardMuted, modifier = Modifier.padding(start = 12.dp)) }
                    Spacer(Modifier.weight(1f))
                    QuietButton("More", onMore, color = c.onNowCard)
                }
            }
        }
    }
}

@Composable
private fun BlockRow(b: TimelineBlock, onClick: () -> Unit) {
    val c = RhythmTheme.colors
    val amber = b.tone == BlockTone.EXTENDED
    RhythmCard(
        modifier = Modifier.alpha(if (b.isPast && !b.isDone) 0.7f else 1f),
        amber = amber,
        onClick = onClick,
        color = if (b.block.isFixed) c.ground else c.surface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    (if (b.isDone) "✓ " else "") + b.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (b.outcome == ExecutionOutcome.SKIPPED) TextDecoration.LineThrough else null,
                )
                Text(b.detail, style = MaterialTheme.typography.bodySmall, color = if (amber) c.amberText else c.muted)
            }
            Text(b.startLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockSheet(b: TimelineBlock, actions: TodayActions, onDismiss: () -> Unit) {
    val c = RhythmTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.ground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(b.rangeLabel, style = MaterialTheme.typography.labelMedium, color = c.muted)
            Text(b.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(b.detail, style = MaterialTheme.typography.bodyMedium, color = if (b.tone == BlockTone.EXTENDED) c.amberText else c.muted)
            Text(b.workLabel, style = MaterialTheme.typography.bodySmall, color = c.muted)
            if (!b.block.isFixed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (b.outcome == ExecutionOutcome.PENDING) PillButton("Start", { actions.onStart(b.block); onDismiss() }, Modifier.weight(1f))
                    PillButton("Done", { actions.onDone(b.block); onDismiss() }, Modifier.weight(1f), kind = if (b.outcome == ExecutionOutcome.PENDING) PillKind.SECONDARY else PillKind.PRIMARY)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Need 30 more", { actions.onNeedMore(b.block); onDismiss() }, Modifier.weight(1f), kind = PillKind.SECONDARY)
                    PillButton("Stop here", { actions.onStopHere(b.block); onDismiss() }, Modifier.weight(1f), kind = PillKind.SECONDARY)
                }
                QuietButton("Skip this block", { actions.onSkip(b.block); onDismiss() })
            }
            PillButton("Open in Google Calendar", { actions.onOpenCalendar(b.block.start); onDismiss() }, Modifier.fillMaxWidth(), kind = PillKind.SECONDARY)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusSheet(title: String, onPick: (Int?) -> Unit) {
    val c = RhythmTheme.colors
    ModalBottomSheet(onDismissRequest = { onPick(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.ground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("How focused were you?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(title, style = MaterialTheme.typography.bodyMedium, color = c.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { n ->
                    PillButton(n.toString(), { onPick(n) }, Modifier.weight(1f), kind = PillKind.SECONDARY, contentDescription = "Focus $n of 5")
                }
            }
            Text("1 is scattered, 5 is fully in it.", style = MaterialTheme.typography.bodySmall, color = c.muted)
            QuietButton("Skip", { onPick(null) })
        }
    }
}
