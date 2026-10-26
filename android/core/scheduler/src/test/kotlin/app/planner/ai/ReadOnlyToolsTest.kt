package app.planner.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadOnlyToolsTest {
    private class Recording : ToolExecutor {
        val ran = mutableListOf<String>()

        override suspend fun execute(name: String, input: JsonObject): ToolOutcome {
            ran += name
            return ToolOutcome("{\"ok\":true}")
        }
    }

    @Test
    fun `reading tools run`() = runBlocking {
        val inner = Recording()
        val tools = ReadOnlyTools(inner)
        for (name in AiToolNames.readOnly) assertFalse(tools.execute(name, JsonObject(emptyMap())).isError)
        assertEquals(AiToolNames.readOnly, inner.ran.toSet())
    }

    @Test
    fun `every tool that changes anything is refused and never reaches the real executor`() = runBlocking {
        val inner = Recording()
        val tools = ReadOnlyTools(inner)
        val writers = AiToolNames.all - AiToolNames.readOnly
        assertTrue(writers.isNotEmpty())
        for (name in writers) {
            val outcome = tools.execute(name, JsonObject(emptyMap()))
            assertTrue(outcome.isError, "$name should be refused")
            assertTrue(outcome.content.contains("read-only"))
        }
        assertTrue(inner.ran.isEmpty())
    }

    @Test
    fun `an unknown tool is refused too`() = runBlocking {
        val inner = Recording()
        assertTrue(ReadOnlyTools(inner).execute("delete_everything", JsonObject(emptyMap())).isError)
        assertTrue(inner.ran.isEmpty())
    }
}
