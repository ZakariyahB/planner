package app.planner.di

import android.content.Context
import app.planner.assistant.AssistantService
import app.planner.assistant.WorkerAssistantClient
import app.planner.assistant.conversation.FileConversationStore
import app.planner.assistant.prompt.PromptBundle
import app.planner.engine.ports.AssistantPort
import app.planner.engine.ports.NowProvider
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.ProposalPort
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.TaskStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Singleton

// Owner: Stream C. The v2 assistant: the propose-only tool loop in :assistant, talking to Claude through the
// Cloudflare Worker. It reaches the calendar only through PlannerQueries (read) and ProposalPort (proposals for the
// approval card); it has no CalendarWriter.
@Module
@InstallIn(SingletonComponent::class)
object AssistantBindings {
    @Provides @Singleton
    fun assistant(
        @ApplicationContext context: Context,
        llm: WorkerAssistantClient,
        queries: PlannerQueries,
        proposals: ProposalPort,
        tasks: TaskStore,
        settings: SettingsStore,
        clock: Clock,
    ): AssistantPort {
        // docs/prompts is bundled as assets (see app/build.gradle.kts); the files are read once, on first use.
        val prompts = lazy {
            PromptBundle.load { name -> context.assets.open(name).bufferedReader().use { it.readText() } }
        }
        return AssistantService(
            llm = llm,
            prompts = { prompts.value },
            queries = queries,
            proposals = proposals,
            tasks = tasks,
            settings = settings,
            store = FileConversationStore(File(context.filesDir, "assistant/conversation-v2.json")),
            now = NowProvider { LocalDateTime.now(clock) },
            clock = { clock.instant() },
        )
    }
}
