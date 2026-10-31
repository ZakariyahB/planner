package app.planner.di

import app.planner.engine.ports.AssistantPort
import app.planner.engine.ports.AssistantReply
import app.planner.engine.ports.ChatRole
import app.planner.engine.ports.ChatTurn
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// Owner: Stream C. Stream 0 binds an echo assistant so every branch builds and runs.
@Module
@InstallIn(SingletonComponent::class)
object AssistantBindings {
    @Provides @Singleton
    fun assistant(): AssistantPort = object : AssistantPort {
        private val turns = MutableStateFlow<List<ChatTurn>>(emptyList())
        override fun observeConversation(): Flow<List<ChatTurn>> = turns
        override suspend fun send(text: String): AssistantReply {
            val reply = "Not connected yet. You said: $text"
            turns.update {
                it + ChatTurn(UUID.randomUUID().toString(), ChatRole.USER, text, Instant.now()) +
                    ChatTurn(UUID.randomUUID().toString(), ChatRole.ASSISTANT, reply, Instant.now())
            }
            return AssistantReply(reply)
        }
        override suspend fun clear() { turns.value = emptyList() }
    }
}
