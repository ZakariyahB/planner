package app.planner.assistant

import app.planner.assistant.conversation.Conversation
import app.planner.assistant.conversation.FileConversationStore
import app.planner.assistant.llm.Message
import app.planner.engine.ports.ChatRole
import app.planner.engine.ports.ChatTurn
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class FileConversationStoreTest {
    private val dir = Files.createTempDirectory("conv").toFile().apply { deleteOnExit() }

    @Test
    fun `saves and loads, and starts empty when there is no file`() = runTest {
        val file = dir.resolve("assistant/conversation.json")
        val store = FileConversationStore(file)
        assertEquals(Conversation(), store.load())
        val c = Conversation(listOf(ChatTurn("1", ChatRole.USER, "hi", Instant.ofEpochMilli(5))), listOf(Message.user("hi")))
        store.save(c)
        store.save(c)
        assertEquals(c, FileConversationStore(file).load())
    }

    @Test
    fun `a corrupt file loads as an empty conversation`() = runTest {
        val file = dir.resolve("bad.json").apply { writeText("{{{") }
        assertEquals(Conversation(), FileConversationStore(file).load())
    }
}
