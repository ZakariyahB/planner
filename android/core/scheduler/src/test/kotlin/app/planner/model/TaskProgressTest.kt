package app.planner.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskProgressTest {
    private val task = Task(id = 3, title = "OS worksheet", estMin = 90)

    @Test
    fun `finishing a task marks it done`() {
        assertEquals(TaskStatus.DONE, TaskProgress.apply(task, minutes = 80, outcome = ProgressOutcome.DONE).status)
    }

    @Test
    fun `partial progress keeps the task open with the remaining time`() {
        val after = TaskProgress.apply(task, minutes = 40, outcome = ProgressOutcome.PARTIAL)
        assertEquals(TaskStatus.PARTIAL, after.status)
        assertEquals(50, after.estMin)
    }

    @Test
    fun `at least five minutes are always left, so it can still be planned`() {
        assertEquals(5, TaskProgress.apply(task, minutes = 89, outcome = ProgressOutcome.PARTIAL).estMin)
        assertEquals(5, TaskProgress.apply(task, minutes = 500, outcome = ProgressOutcome.PARTIAL).estMin)
    }

    @Test
    fun `zero minutes of progress changes nothing but the status`() {
        val after = TaskProgress.apply(task, minutes = 0, outcome = ProgressOutcome.PARTIAL)
        assertEquals(90, after.estMin)
    }

    @Test
    fun `giving up drops the task and keeps its estimate`() {
        val after = TaskProgress.apply(task, minutes = 10, outcome = ProgressOutcome.DROPPED)
        assertEquals(TaskStatus.DROPPED, after.status)
        assertEquals(90, after.estMin)
    }

    @Test
    fun `negative minutes are refused`() {
        assertFailsWith<IllegalArgumentException> { TaskProgress.apply(task, minutes = -5, outcome = ProgressOutcome.PARTIAL) }
    }

    @Test
    fun `outcomes are read from the words the model and the app use`() {
        assertEquals(ProgressOutcome.DONE, ProgressOutcome.parse("done"))
        assertEquals(ProgressOutcome.PARTIAL, ProgressOutcome.parse("PARTIAL"))
        assertEquals(ProgressOutcome.DROPPED, ProgressOutcome.parse("dropped"))
        assertEquals(null, ProgressOutcome.parse("whatever"))
    }
}
