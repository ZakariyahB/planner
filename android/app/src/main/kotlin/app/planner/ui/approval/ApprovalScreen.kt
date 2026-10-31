package app.planner.ui.approval

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.RecurrenceScope
import app.planner.ui.model.ApplyReport
import app.planner.ui.model.ApprovalCards
import app.planner.ui.model.ChangeCardUi
import app.planner.ui.model.ReportLine
import app.planner.ui.model.SpanUi
import app.planner.ui.rhythm.FlagChip
import app.planner.ui.rhythm.PillButton
import app.planner.ui.rhythm.PillKind
import app.planner.ui.rhythm.QuietButton
import app.planner.ui.rhythm.RhythmCard
import app.planner.ui.theme.RhythmTheme

/** Actions the approval UI can take; [ApprovalViewModel] implements them. */
class ApprovalActions(
    val onToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onScope: (String, RecurrenceScope) -> Unit = { _, _ -> },
    val onApprove: () -> Unit = {},
    val onReject: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onNext: () -> Unit = {},
)

/** Shows the open proposal: a bottom sheet for one change, the full-screen list for several. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalHost(vm: ApprovalViewModel, onClose: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val actions = ApprovalActions(
        onToggle = vm::setApproved,
        onScope = vm::setScope,
        onApprove = { vm.approve() },
        onReject = { vm.reject(); onClose() },
        onUndo = { ui.record?.let { vm.undo(it) } },
        onClose = { vm.close(); onClose() },
        onNext = { vm.open(null) },
    )
    if (ui.asSheet) {
        ModalBottomSheet(
            onDismissRequest = actions.onClose,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = RhythmTheme.colors.ground,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
                ApprovalBody(ui, actions, sheet = true)
            }
        }
    } else {
        BackHandler(onBack = actions.onClose)
        Surface(Modifier.fillMaxSize(), color = RhythmTheme.colors.ground) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = RhythmTheme.colors.ink) }
                    Text("Review changes", style = MaterialTheme.typography.titleMedium)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { ApprovalHeader(ui) }
                    val report = ui.report
                    if (report != null) {
                        items(report.lines, key = { "r-" + it.changeId }) { ReportCard(it) }
                    } else {
                        items(ui.cards?.cards.orEmpty(), key = { it.id }) { card ->
                            ChangeCard(
                                card,
                                approved = ui.decisions[card.id] != false,
                                scope = ui.scopes[card.id] ?: card.scope,
                                showToggle = true,
                                actions = actions,
                            )
                        }
                    }
                }
                Box(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) { ApprovalButtons(ui, actions, sheet = false) }
            }
        }
    }
}

@Composable
private fun ApprovalHeader(ui: ApprovalUi) {
    val c = RhythmTheme.colors
    val cards = ui.cards
    val report = ui.report
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            cards == null -> {
                Text("Nothing waiting", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text("Proposals from you, Ask, check-ins and replans appear here before anything is written.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
            report != null -> ReportHeader(report)
            else -> {
                Text(cards.sourceLine, style = MaterialTheme.typography.labelMedium, color = c.muted)
                Text(cards.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                val count = cards.cards.size
                Text(
                    (if (count == 1) "One change. " else "$count changes. ") + "Nothing is written to Google until you approve.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.muted,
                )
                cards.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = c.muted) }
            }
        }
        ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.error) }
    }
}

@Composable
private fun ReportHeader(report: ApplyReport) {
    val c = RhythmTheme.colors
    Text(report.headline, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    report.explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.muted) }
}

@Composable
private fun ApprovalBody(ui: ApprovalUi, actions: ApprovalActions, sheet: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ApprovalHeader(ui)
        val report = ui.report
        if (report != null) {
            report.lines.forEach { ReportCard(it) }
        } else {
            ui.cards?.cards?.forEach { card ->
                ChangeCard(card, approved = true, scope = ui.scopes[card.id] ?: card.scope, showToggle = false, actions = actions)
            }
        }
        ApprovalButtons(ui, actions, sheet)
    }
}

@Composable
private fun ApprovalButtons(ui: ApprovalUi, actions: ApprovalActions, sheet: Boolean) {
    val c = RhythmTheme.colors
    when {
        ui.busy -> Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = c.accent)
            Text("  Writing to Google and checking…", style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        ui.report != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ui.report.canUndo) PillButton("Undo", actions.onUndo, kind = PillKind.SECONDARY)
                PillButton(if (ui.otherPending > 0) "Next proposal" else "Close", if (ui.otherPending > 0) actions.onNext else actions.onClose, Modifier.weight(1f))
            }
        }
        ui.changeSet == null -> PillButton("Close", actions.onClose, Modifier.fillMaxWidth())
        else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(if (sheet) "Not now" else "Reject all", if (sheet) actions.onClose else actions.onReject, kind = PillKind.SECONDARY)
                PillButton(
                    ApprovalCards.approveLabel(ui.selectedCount, ui.total),
                    actions.onApprove,
                    Modifier.weight(1f),
                    enabled = ui.selectedCount > 0,
                )
            }
            if (sheet) QuietButton("Reject this change", actions.onReject, color = c.muted)
            if (ui.otherPending > 0) Text("${ui.otherPending} more waiting after this one", style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

@Composable
fun ChangeCard(card: ChangeCardUi, approved: Boolean, scope: RecurrenceScope, showToggle: Boolean, actions: ApprovalActions) {
    val c = RhythmTheme.colors
    val warn = card.flags.any { it.warning }
    RhythmCard(amber = warn, padding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(card.kindLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = c.accent, letterSpacing = 0.5.sp, modifier = Modifier.weight(1f))
                if (card.recurring && !showToggle) Text(ApprovalCards.scopeLabel(scope), style = MaterialTheme.typography.labelMedium, color = c.muted)
                if (showToggle) {
                    Row(
                        Modifier.heightIn(min = 48.dp).toggleable(value = approved, role = Role.Checkbox, onValueChange = { actions.onToggle(card.id, it) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = approved, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent))
                        Text("Approve", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = card.description }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleLarge,
                    textDecoration = if (card.type == ChangeType.REMOVE) TextDecoration.LineThrough else null,
                )
                card.previousTitle?.let { Text("was “$it”", style = MaterialTheme.typography.bodySmall, color = c.muted) }
                Text(listOfNotNull(card.dateLabel.ifBlank { null }, card.calendarMove).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.muted)
                BeforeAfter(card.before, card.after, card.type)
                card.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
                if (card.flags.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        card.flags.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { FlagChip(it.label, it.warning) } }
                        }
                    }
                }
                card.targetLine?.let { Text("Exact event: $it", style = MaterialTheme.typography.labelMedium, color = c.muted) }
                if (card.status != ChangeStatus.PROPOSED) {
                    Text(card.status.name.lowercase() + (card.problem?.let { ": $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.amberText)
                }
            }
            if (card.recurring) ScopePicker(card, scope, actions)
        }
    }
}

@Composable
private fun BeforeAfter(before: SpanUi?, after: SpanUi?, type: ChangeType) {
    val c = RhythmTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (before != null) {
            SpanColumn("Before", before, c.fixedFill, bold = false, Modifier.weight(1f))
        }
        if (before != null && after != null) {
            Text("→", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { contentDescription = "becomes" })
        }
        if (after != null) {
            SpanColumn(if (type == ChangeType.ADD) "New" else "After", after, c.accent, bold = true, Modifier.weight(1f))
        } else if (type == ChangeType.REMOVE) {
            Text("Removed", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = c.amberText, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpanColumn(label: String, span: SpanUi, fill: Color, bold: Boolean, modifier: Modifier) {
    val c = RhythmTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.muted)
        val track = c.hairline
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val start = size.width * span.startFraction
            val width = (size.width * span.endFraction - start).coerceAtLeast(size.height)
            drawRoundRect(fill, topLeft = Offset(start.coerceAtMost(size.width - width), 0f), size = Size(width, size.height), cornerRadius = r)
        }
        Text(span.label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = if (bold) c.ink else c.muted)
    }
}

@Composable
private fun ScopePicker(card: ChangeCardUi, scope: RecurrenceScope, actions: ApprovalActions) {
    val c = RhythmTheme.colors
    val verb = when (card.type) {
        ChangeType.REMOVE -> "remove"
        ChangeType.ADD -> "add"
        else -> "change"
    }
    Column(Modifier.selectableGroup()) {
        Text("Repeating event: $verb", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.semantics { heading() })
        RecurrenceScope.entries.forEach { option ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = option == scope, role = Role.RadioButton, onClick = { actions.onScope(card.id, option) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == scope, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.accent))
                Text(ApprovalCards.scopeOption(option), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ReportCard(line: ReportLine) {
    val c = RhythmTheme.colors
    val (badge, color) = when (line.status) {
        ChangeStatus.VERIFIED -> "✓ VERIFIED" to c.accent
        ChangeStatus.APPLIED -> "WRITTEN" to c.accent
        ChangeStatus.STALE -> "! STALE" to c.amberText
        ChangeStatus.FAILED -> "✕ FAILED" to c.error
        else -> line.status.name to c.muted
    }
    RhythmCard(amber = line.status == ChangeStatus.STALE || line.status == ChangeStatus.FAILED) {
        Row(Modifier.fillMaxWidth()) {
            Text(line.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(badge, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
        }
        Text(line.text, style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}
