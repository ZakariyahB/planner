package app.planner.assistant

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** newplan 2: the AI layer has no reference to CalendarWriter. If :calendar ever lands on this classpath, this fails. */
class AssistantIsolationTest {
    @Test
    fun `calendar writer is not on the assistant classpath`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("app.planner.gcal.CalendarWriter") }
        assertFailsWith<ClassNotFoundException> { Class.forName("app.planner.gcal.ApprovalGate") }
    }
}
