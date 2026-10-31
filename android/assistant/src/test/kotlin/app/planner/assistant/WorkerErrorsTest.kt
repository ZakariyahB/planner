package app.planner.assistant

import app.planner.assistant.llm.LlmFailure
import app.planner.assistant.llm.WorkerErrors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerErrorsTest {
    @Test
    fun `statuses map to plain failures and words`() {
        assertEquals(LlmFailure.SIGN_IN, WorkerErrors.failureFor(401))
        assertEquals(LlmFailure.CAP_REACHED, WorkerErrors.failureFor(402))
        assertEquals(LlmFailure.TOO_LONG, WorkerErrors.failureFor(413))
        assertEquals(LlmFailure.BUSY, WorkerErrors.failureFor(429))
        assertEquals(LlmFailure.OTHER, WorkerErrors.failureFor(502))
        assertTrue("model in Settings" in WorkerErrors.messageFor(400, """{"error":"unknown model"}"""))
        assertTrue("(503)" in WorkerErrors.messageFor(503))
    }
}
