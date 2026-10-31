package app.planner.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.engine.ports.ChatRole
import app.planner.ui.rhythm.PillButton
import app.planner.ui.rhythm.PillKind
import app.planner.ui.rhythm.RhythmCard
import app.planner.ui.rhythm.ScreenHeader
import app.planner.ui.theme.RhythmTheme

class AskActions(
    val onDraft: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onReview: (String) -> Unit = {},
)

@Composable
fun AskRoute(vm: AskViewModel, onOpenApproval: (String?) -> Unit, bottomPadding: Dp) {
    val ui by vm.state.collectAsStateWithLifecycle()
    AskContent(ui, AskActions(vm::setDraft, { vm.send() }, { vm.clear() }, { onOpenApproval(it) }), bottomPadding)
}

@Composable
fun AskContent(ui: AskUi, actions: AskActions, bottomPadding: Dp = 96.dp) {
    val c = RhythmTheme.colors
    val list = rememberLazyListState()
    LaunchedEffect(ui.turns.size, ui.busy) {
        val last = ui.turns.size + (if (ui.busy) 1 else 0)
        if (last > 0) list.animateScrollToItem(last)
    }
    Column(Modifier.fillMaxSize().imePadding().padding(bottom = bottomPadding)) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                ScreenHeader("Changes go to you for approval first", "Ask", "Tell me what changed. I work out the times with the planner and never write to your calendar myself.") {
                    if (ui.turns.isNotEmpty()) IconButton(onClick = actions.onClear) { Icon(Icons.Outlined.Delete, contentDescription = "Clear the conversation", tint = c.muted) }
                }
            }
            items(ui.turns, key = { it.turn.id }) { t -> TurnRow(t, actions) }
            if (ui.busy) item(key = "busy") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = c.accent)
                    Text("Working it out…", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
            }
        }
        ui.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.error, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AskViewModel.quickPrompts.forEach { (label, text) -> PillButton(label, { actions.onDraft(text) }, kind = PillKind.SECONDARY) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ui.draft,
                onValueChange = actions.onDraft,
                placeholder = { Text("Tell me what changed") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { actions.onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.line, cursorColor = c.accent,
                    focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
                ),
            )
            IconButton(onClick = actions.onSend, enabled = ui.draft.isNotBlank() && !ui.busy) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send", tint = if (ui.draft.isNotBlank()) c.accent else c.muted)
            }
        }
    }
}

@Composable
private fun TurnRow(t: AskTurnUi, actions: AskActions) {
    val c = RhythmTheme.colors
    when (t.turn.role) {
        ChatRole.NOTE -> Text(t.turn.text, style = MaterialTheme.typography.bodySmall, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        ChatRole.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                t.turn.text,
                style = MaterialTheme.typography.bodyLarge,
                color = c.onAccent,
                modifier = Modifier.widthIn(max = 300.dp).background(c.accent, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        ChatRole.ASSISTANT -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                t.turn.text,
                style = MaterialTheme.typography.bodyLarge,
                color = c.ink,
                modifier = Modifier.widthIn(max = 320.dp).background(c.surface, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            )
            t.proposal?.let { p ->
                RhythmCard(amber = false, padding = PaddingValues(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.summary, style = MaterialTheme.typography.titleMedium)
                            Text(p.statusLabel, style = MaterialTheme.typography.bodySmall, color = if (p.pending) c.accent else c.muted)
                        }
                        if (p.pending) PillButton("Review", { actions.onReview(p.id) }, contentDescription = "Review ${p.summary}")
                    }
                }
            }
        }
    }
}
