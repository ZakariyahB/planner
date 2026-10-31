package app.planner.assistant

import app.planner.assistant.conversation.Conversation
import app.planner.assistant.conversation.ConversationCodec
import app.planner.assistant.conversation.ConversationStore
import app.planner.assistant.llm.ContentBlock
import app.planner.assistant.llm.LlmClient
import app.planner.assistant.llm.LlmException
import app.planner.assistant.llm.Message
import app.planner.assistant.llm.Purpose
import app.planner.assistant.llm.Role
import app.planner.assistant.prompt.ContextSummary
import app.planner.assistant.prompt.PromptBundle
import app.planner.assistant.tools.AssistantTools
import app.planner.engine.ports.AssistantPort
import app.planner.engine.ports.AssistantReply
import app.planner.engine.ports.ChatRole
import app.planner.engine.ports.ChatTurn
import app.planner.engine.ports.NowProvider
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.ProposalPort
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.TaskStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The in-app assistant ([AssistantPort]). Each message runs the tool loop with the v2 prompt and tools; anything
 * the model wants changed comes back as a ChangeSet proposal (through [ProposalPort]) whose id is returned so the
 * chat can show the approval card inline. Nothing here can write to a calendar.
 */
class AssistantService(
    private val llm: LlmClient,
    private val prompts: () -> PromptBundle,
    private val queries: PlannerQueries,
    private val proposals: ProposalPort,
    private val tasks: TaskStore,
    private val settings: SettingsStore,
    private val store: ConversationStore,
    private val now: NowProvider,
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Past this, the model's history starts afresh (the chat on screen is kept). Keeps requests under the Worker's limit. */
    private val maxHistoryChars: Int = 120_000,
    private val maxRounds: Int = 8,
) : AssistantPort {
    private val mutex = Mutex()
    private val turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    private var history: List<Message> = emptyList()
    private var loaded = false

    private val tools by lazy { AssistantTools(queries, proposals, tasks, now) }

    override fun observeConversation(): Flow<List<ChatTurn>> = flow {
        mutex.withLock { ensureLoaded() }
        emitAll(turns)
    }

    override suspend fun clear() = mutex.withLock {
        loaded = true
        history = emptyList()
        turns.value = emptyList()
        store.save(Conversation())
    }

    override suspend fun send(text: String): AssistantReply = mutex.withLock {
        ensureLoaded()
        val words = text.trim()
        if (words.isEmpty()) return@withLock AssistantReply("", error = "Type a message first.")
        turns.value = turns.value + turn(ChatRole.USER, words)

        var past = history
        if (ConversationCodec.encode(Conversation(history = past)).length > maxHistoryChars) {
            // Trimming old turns would edit the history the model already saw, so start it afresh instead.
            past = emptyList()
            turns.value = turns.value + turn(ChatRole.NOTE, "The assistant started a fresh conversation; it no longer sees the earlier messages.")
        }

        val bundle = prompts()
        val model = runCatching { settings.current().assistantModel }.getOrNull()
        val user = Message(Role.USER, listOf(ContentBlock.Text(context()), ContentBlock.Text(words)))
        val result = try {
            ToolLoop(llm, tools, maxRounds).run(bundle.system, bundle.tools, past, user, model = model, purpose = Purpose.CHAT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            return@withLock failed(e.message ?: "The assistant is unavailable.")
        } catch (e: Exception) {
            return@withLock failed("Something went wrong with the assistant. Try again.")
        }

        val reply = result.reply.ifBlank {
            if (result.proposalIds.isNotEmpty()) "I've put that on the approval card." else "Done."
        }
        val first = result.proposalIds.firstOrNull()
        val extra = result.proposalIds.drop(1).map { id -> turn(ChatRole.NOTE, "Another proposal is waiting for approval.", id) }
        turns.value = turns.value + turn(ChatRole.ASSISTANT, reply, first) + extra
        history = past + result.newMessages
        persist()
        AssistantReply(reply, result.proposalIds)
    }

    private suspend fun failed(message: String): AssistantReply {
        turns.value = turns.value + turn(ChatRole.NOTE, message)
        persist()
        return AssistantReply("", error = message)
    }

    private suspend fun context(): String = try {
        val nowAt = now.now()
        val monday = nowAt.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        ContextSummary.render(
            now = nowAt,
            week = queries.week(monday),
            tasks = tasks.all(),
            needsALook = tasks.observeNeedsALook().first().size,
            settings = settings.current(),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "[App context as of ${now.now()}: the week could not be read just now; use the tools.]"
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val saved = try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Conversation()
        }
        history = saved.history
        turns.value = saved.turns
        loaded = true
    }

    private suspend fun persist() {
        try {
            store.save(Conversation(turns.value, history))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The chat still works this session; it just will not be remembered.
        }
    }

    private fun turn(role: ChatRole, text: String, proposalId: String? = null) = ChatTurn(newId(), role, text, clock(), proposalId)
}
