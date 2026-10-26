package app.planner.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressPreviewTest {
    private val task = Task(id = 3, title = "OS worksheet", estMin = 90)

    @Test
    fun `partial work says how much is left and that it will be planned again`() {
        assertEquals(
            "50 min left. It will be planned again at the next free time.",
            ProgressPreview.line(task, minutes = 40, outcome = ProgressOutcome.PARTIAL),
        )
    }

    @Test
    fun `what is left is written in hours once it is an hour or more`() {
        val long = task.copy(estMin = 150)
        assertEquals("1 h 30 min left. It will be planned again at the next free time.", ProgressPreview.line(long, 60, ProgressOutcome.PARTIAL))
        assertEquals("2 h left. It will be planned again at the next free time.", ProgressPreview.line(long, 30, ProgressOutcome.PARTIAL))
    }

    @Test
    fun `using up the whole estimate still leaves the smallest task and says so`() {
        assertEquals(
            "5 min left, the least a task can take. It will be planned again at the next free time.",
            ProgressPreview.line(task, minutes = 120, outcome = ProgressOutcome.PARTIAL),
        )
    }

    @Test
    fun `the preview agrees with what saving does`() {
        for (minutes in listOf(15, 30, 45, 60, 85, 90, 200)) {
            val left = TaskProgress.apply(task, minutes, ProgressOutcome.PARTIAL).estMin
            assertTrue(ProgressPreview.line(task, minutes, ProgressOutcome.PARTIAL).startsWith(ProgressPreview.duration(left)), "minutes=$minutes")
        }
    }

    @Test
    fun `finishing says the task will not be planned again`() {
        assertEquals("Done. It won't be planned again.", ProgressPreview.line(task, 0, ProgressOutcome.DONE))
        assertEquals("Done, with 40 min logged. It won't be planned again.", ProgressPreview.line(task, 40, ProgressOutcome.DONE))
    }

    @Test
    fun `dropping says the task leaves the plan`() {
        assertEquals("Dropped. It leaves the plan.", ProgressPreview.line(task, 0, ProgressOutcome.DROPPED))
        assertEquals("Dropped, with 20 min logged. It leaves the plan.", ProgressPreview.line(task, 20, ProgressOutcome.DROPPED))
    }

    @Test
    fun `partial progress needs some minutes`() {
        assertNotNull(ProgressPreview.problem(ProgressOutcome.PARTIAL, 0))
        assertNull(ProgressPreview.problem(ProgressOutcome.PARTIAL, 15))
    }

    @Test
    fun `finishing or dropping needs no minutes`() {
        assertNull(ProgressPreview.problem(ProgressOutcome.DONE, 0))
        assertNull(ProgressPreview.problem(ProgressOutcome.DROPPED, 0))
    }

    @Test
    fun `more than the longest task is refused in plain words`() {
        val reason = ProgressPreview.problem(ProgressOutcome.DONE, Task.MAX_EST_MIN + 1)
        assertNotNull(reason)
        assertTrue("12 hours" in reason, reason)
        assertNull(ProgressPreview.problem(ProgressOutcome.DONE, Task.MAX_EST_MIN))
    }

    @Test
    fun `typed minutes are whole numbers or nothing`() {
        assertEquals(25, ProgressPreview.parseMinutes("25"))
        assertEquals(25, ProgressPreview.parseMinutes(" 25 "))
        assertEquals(0, ProgressPreview.parseMinutes("0"))
        for (text in listOf("", "  ", "abc", "-5", "2.5", "1h", "99999999999")) assertNull(ProgressPreview.parseMinutes(text), text)
    }

    @Test
    fun `the quick choices are fifteen to sixty minutes`() {
        assertEquals(listOf(15, 30, 45, 60), ProgressPreview.QUICK_MINUTES)
    }
}
