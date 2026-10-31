# Stream F: data — notes for the final docs

## What exists

- **Codecs** (`android/engine/.../engine/codec/PlannerCodec.kt`, `Dtos.kt`, `TimeSerializers.kt`): `PlannerCodec.task`, `.settings`, `.eventRef`, `.eventTime`, `.eventSnapshot`, `.eventDraft`, `.change`, `.changeSet`, `.inverseOp`, `.appliedRecord`, `.executionLog`. Each is a `JsonCodec<T>` with `encode`, `decode`, `encodeList`, `decodeList`. The domain types are contracts without annotations, so each one has a private DTO with mappers. The DTO defaults come from the domain types, so the two cannot drift.
- **Stored format**: java.time values are ISO-8601 strings (exact to the nanosecond). Sealed types carry a `"kind"` field (`timed`/`allDay`, `delete`/`restore`/`revert`). Every field is written, defaults included. Nulls are left out. Sets and maps are written in a fixed order, so the same value always encodes to the same text.
- **Tolerant decoding**: unknown keys are ignored. A missing field takes the domain default. A null where a default exists takes the default. An unknown enum value takes the default; unknown names in flag sets, writable calendars and prayer offsets are dropped. An unknown zone id becomes Europe/London. Some fallbacks are chosen to be safe: an unknown task category becomes PERSONAL (the routing rule's "anything else"). An unknown `ChangeResult.status` becomes FAILED, so a write we cannot read back is never reported as verified. A snapshot without etag/updated reads as stale at pre-flight. JSON that is not the record at all (no id, unknown sealed kind, not JSON) still throws.
- **StoreKeys** (`engine/codec/StoreKeys.kt`): the key columns a database keeps beside each JSON body: wall-clock and instant sort keys, and the event date range. The range uses the same rule as `InMemorySnapshotStore`: a timed event covers its start date through its end date inclusive, so an event ending at 00:00 also counts on that next day. A randomised test checks that the rule selects exactly what the fake selects.
- **StoreContract** (`engine/codec/StoreContract.kt`): 35 cases describing the five storage ports, in plain code (no test framework) so the same cases can run against any implementation. `StoreContractTest` runs them against the fakes, and checks that a store which drops a field fails.
- **Learning** (`engine/learn/DurationLearning.kt`): actual vs planned minutes per title (chunk labels like `(1/2)`, case and spacing ignored) and per category (from the task, else from the event's calendar). It reports totals, the median actual/planned ratio, mean focus, and counts of stopped and skipped blocks. Only DONE/EXTENDED blocks with actual start and end are samples. `DurationLearning.summary(report)` gives a short text block for the assistant's system prompt (Stream C can call it with `ExecutionLogStore.history(since)` and `TaskStore.all()`). Minutes are wall-clock, so a block that crosses the clock change is off by an hour.
- **Room** (`android/app/.../store/*`): `PlannerStoreDatabase`, file `planner_v2.db`, version 1, `exportSchema = true`, separate from the legacy `AppDatabase` (`planner.db`, untouched). Tables: `tasks`, `needs_a_look`, `change_sets`, `applied_records`, `event_snapshots`, `sync_tokens`, `sync_stamp`, `execution_logs`, `settings`. `RoomTaskStore`, `RoomChangeSetStore`, `RoomSnapshotStore`, `RoomExecutionLogStore` and `RoomSettingsStore` are bound in `di/StoreBindings.kt`.

## Differences from the plan, and why

- **Whole records as JSON plus key columns**, rather than one column per scalar field and JSON only for the complex ones. Each table has only the columns its queries filter or sort on, plus a `body` holding the record through `PlannerCodec`. Reasons: all the mapping logic is in pure code and covered by JVM tests; adding a field to a domain type needs no migration (old rows decode with the default); and the key columns come from `StoreKeys`, so order and date ranges match the fakes. Changing a query, or adding a queried column, still needs a Room migration and a version bump.
- **The last applied record** is the one saved most recently, not the one with the latest `appliedAt`. That is what the fake does. An undo re-saves the record with `undone = true`, and that keeps it as the last one. Room keeps this with a `seq` column that grows on every save.
- The contract suite lives in `main` (not `test`), so an instrumented test in `:app` can reuse it.

## Setup and build

- No setup. The database is created on first use. The first `:app` build writes `android/app/schemas/app.planner.store.PlannerStoreDatabase/1.json` (Room's exported schema); commit it with the integration, and keep every later version's file, because migrations are checked against them.
- The app now keeps v2 data on disk. Before, the stores were in memory and were lost on restart. To start from empty: clear app data, or delete `planner_v2.db` only (the legacy `planner.db` stays).

## Running the store contract against Room (device or emulator)

Needs the Gradle change requested in `CHANGES.md` (instrumentation runner and `androidx.test` dependencies). Then add `android/app/src/androidTest/kotlin/app/planner/store/RoomStoreContractTest.kt`:

```kotlin
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
```

Run it with `cd android && ./gradlew :app:connectedDebugAndroidTest` with a device or emulator connected. A failure lists each failing case as `name: problem`.

## What to test by hand

1. Fresh install: open the app; nothing crashes; Settings shows the defaults.
2. Change a setting (wake time, a prayer offset, writable calendars), force-stop the app and reopen it: the change is kept.
3. After Stream B is merged: sync, force-stop and reopen. Today and Week show the cached events before the next sync finishes. The "last synced" stamp is kept.
4. Propose a change (any source), force-stop before approving and reopen: the approval card is still there. Approve and apply, force-stop and reopen: Undo still offers the last applied set.
5. Start and Done on a block (after Stream E), then reopen: the execution log for that day is still there.
6. Optional: with `adb shell run-as app.planner ls databases` you should see both `planner.db` (legacy) and `planner_v2.db`.
