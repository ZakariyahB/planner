package app.planner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.ai.AiFailure
import app.planner.ai.ChatItem
import app.planner.ai.Proposal
import app.planner.ai.Role
import app.planner.scheduler.ChangeSize

private val SUGGESTIONS = listOf(
    "Halaqa ran over by an hour",
    "Plan my evening",
    "Why am I so tired lately?",
    "Add PintOS 3h, due Friday",
)

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val proposals by vm.proposals.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var listening by remember { mutableStateOf(false) }
    var voiceProblem by remember { mutableStateOf<String?>(null) }
    val voice = remember {
        VoiceInput(
            context = context,
            onTranscript = { text, _ -> vm.setDraft(text) }, // lands in the box to read and edit before sending
            onListening = { listening = it },
            onProblem = { voiceProblem = it },
        )
    }
    DisposableEffect(voice) { onDispose { voice.stop() } }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start() else voiceProblem = "Allow the microphone to use voice."
    }
    val onMic = {
        voiceProblem = null
        if (listening) {
            voice.stop()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.start()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(items.size, proposals.size, busy) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Ask", style = MaterialTheme.typography.headlineMedium)
                    if (items.isNotEmpty()) TextButton(onClick = vm::clearConversation) { Text("Clear") }
                }
            }
            if (items.isEmpty()) {
                item { EmptyChat(onPick = vm::send) }
            }
            items(items, key = { it.id }) { Bubble(it) }
            items(proposals, key = { it.id }) { ProposalCard(it, onApprove = { vm.approve(it.id) }, onDismiss = { vm.dismiss(it.id) }, onChange = { vm.setDraft("Change that: ") }) }
            if (busy) item { Text("Thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            problem?.let { p ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.message, color = MaterialTheme.colorScheme.error)
                        if (p.failure == AiFailure.SIGN_IN) Button(onClick = { vm.signIn(context) }) { Text("Sign in") }
                        Text("Planning, ticking tasks and the calendar still work without the assistant.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        voiceProblem?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = vm::setDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (listening) "Listening…" else "Ask or tell me something") },
                maxLines = 4,
            )
            OutlinedButton(onClick = onMic) { Text(if (listening) "Stop" else "Mic") }
            Button(onClick = { vm.send(draft) }, enabled = draft.isNotBlank() && !busy) { Text("Send") }
        }
    }
}

@Composable
private fun EmptyChat(onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "I can move things when plans change, add tasks, and coach you on sleep and workload. I only change the plan through the planner's rules, and I ask before anything big.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUGGESTIONS.forEach { s -> AssistChip(onClick = { onPick(s) }, label = { Text(s) }) }
        }
    }
}

@Composable
private fun Bubble(item: ChatItem) {
    val mine = item.role == Role.USER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                )
                .padding(12.dp),
        ) { Text(item.text) }
        if (item.tools.isNotEmpty()) {
            Text(
                item.tools.joinToString(" · ") { it.label },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProposalCard(proposal: Proposal, onApprove: () -> Unit, onDismiss: () -> Unit, onChange: () -> Unit) {
    val problems = proposal.validation.violations.filter { it.hard }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (proposal.size == ChangeSize.BIG) "Needs your OK" else "Suggested change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(proposal.summary, style = MaterialTheme.typography.bodyLarge)
        problems.forEach { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApprove, enabled = problems.isEmpty()) { Text("Approve") }
            OutlinedButton(onClick = onChange) { Text("Change") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
