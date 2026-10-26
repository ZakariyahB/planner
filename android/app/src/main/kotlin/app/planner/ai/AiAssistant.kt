package app.planner.ai

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

@Module
@InstallIn(SingletonComponent::class)
abstract class AiBindings {
    @Binds abstract fun llmClient(impl: WorkerLlmClient): LlmClient

    @Binds abstract fun toolExecutor(impl: AiToolExecutor): ToolExecutor
}

/** Puts the system prompt, the tool definitions and the saved conversation together and runs the agent. */
@Singleton
class AiAssistant @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llm: LlmClient,
    private val tools: ToolExecutor,
    private val clock: Clock,
) {
    // docs/prompts is bundled as assets; both files are read once.
    private val system: String by lazy { context.assets.open("system.md").bufferedReader().use { it.readText() } }
    private val toolSchemas: JsonArray by lazy {
        Json.parseToJsonElement(context.assets.open("tools.json").bufferedReader().use { it.readText() }).jsonArray
    }

    /** [readOnly] lets the model look at the day but refuses every tool that changes anything (unattended runs). */
    suspend fun ask(history: List<ChatEntry>, text: String, purpose: Purpose = Purpose.CHAT, readOnly: Boolean = false): AgentResult =
        Agent(llm, if (readOnly) ReadOnlyTools(tools) else tools, system, toolSchemas).run(
            history = ChatHistory.toMessages(history),
            userText = Preamble.withUserText(ZonedDateTime.now(clock), text),
            purpose = purpose,
        )
}
