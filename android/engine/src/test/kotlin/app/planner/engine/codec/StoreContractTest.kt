package app.planner.engine.codec

import app.planner.engine.fakes.InMemoryChangeSetStore
import app.planner.engine.fakes.InMemoryExecutionLogStore
import app.planner.engine.fakes.InMemorySettingsStore
import app.planner.engine.fakes.InMemorySnapshotStore
import app.planner.engine.fakes.InMemoryTaskStore
import app.planner.engine.model.Task
import app.planner.engine.ports.TaskStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The shared store contract against the in-memory fakes. The Room stores run the same cases on a device (notes/F.md). */
class StoreContractTest {
    private fun fakes(tasks: TaskStore = InMemoryTaskStore()) = StoreContract.Stores(
        tasks, InMemoryChangeSetStore(), InMemorySnapshotStore(), InMemoryExecutionLogStore(), InMemorySettingsStore(),
    )

    @Test
    fun `the in-memory fakes satisfy every case`() = runBlocking {
        assertEquals(emptyList(), StoreContract { fakes() }.runAll())
    }

    @Test
    fun `case names are unique and cover every store`() {
        val names = StoreContract { fakes() }.cases.map { it.name }
        assertEquals(names.size, names.toSet().size)
        for (prefix in listOf("tasks:", "change sets:", "snapshots:", "logs:", "settings:")) {
            assertTrue(names.count { it.startsWith(prefix) } >= 3, prefix)
        }
    }

    /** A task store that loses the notes field, to prove the cases can fail. */
    private class ForgetfulTaskStore(private val inner: InMemoryTaskStore = InMemoryTaskStore()) : TaskStore by inner {
        override suspend fun upsert(tasks: List<Task>) = inner.upsert(tasks.map { it.copy(notes = null) })
    }

    @Test
    fun `a store that drops a field fails the contract`() = runBlocking {
        val failures = StoreContract { fakes(ForgetfulTaskStore()) }.runAll()
        assertTrue(failures.any { it.startsWith("tasks: upsert then get and all return every field") }, failures.toString())
        assertTrue(failures.all { it.startsWith("tasks:") }, failures.toString())
    }
}
