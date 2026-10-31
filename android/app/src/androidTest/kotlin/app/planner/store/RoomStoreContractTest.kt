package app.planner.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.planner.engine.codec.StoreContract
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomStoreContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<PlannerStoreDatabase>()

    // A fresh in-memory database per case.
    private val contract = StoreContract {
        val db = PlannerStoreDatabase.inMemory(context).also(opened::add)
        StoreContract.Stores(RoomTaskStore(db), RoomChangeSetStore(db), RoomSnapshotStore(db), RoomExecutionLogStore(db), RoomSettingsStore(db))
    }

    @After fun close() = opened.forEach { it.close() }

    @Test fun roomStoresSatisfyTheContract() = runBlocking {
        assertEquals(emptyList<String>(), contract.runAll())
    }
}
