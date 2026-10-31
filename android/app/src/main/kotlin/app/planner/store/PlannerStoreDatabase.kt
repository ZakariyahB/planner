package app.planner.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The v2 local store (newplan section 4): tasks, needs-a-look, change sets, applied records, event snapshots, sync
 * tokens and stamp, execution logs and settings. Separate from the legacy AppDatabase (planner.db), which is untouched.
 *
 * Schema changes need a Migration and a version bump; exported schemas land in android/app/schemas. Fields added to a
 * domain type need neither, because records are JSON with tolerant decoding (see StoreEntities.kt).
 */
@Database(
    entities = [
        TaskRow::class,
        NeedsALookRow::class,
        ChangeSetRow::class,
        AppliedRecordRow::class,
        EventSnapshotRow::class,
        SyncTokenRow::class,
        SyncStampRow::class,
        ExecutionLogRow::class,
        SettingsRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PlannerStoreDatabase : RoomDatabase() {
    abstract fun tasks(): TasksDao
    abstract fun changeSets(): ChangeSetsDao
    abstract fun snapshots(): SnapshotsDao
    abstract fun executionLogs(): ExecutionLogsDao
    abstract fun settings(): SettingsRowDao

    companion object {
        const val FILE_NAME = "planner_v2.db"

        fun open(context: Context): PlannerStoreDatabase =
            Room.databaseBuilder(context.applicationContext, PlannerStoreDatabase::class.java, FILE_NAME).build()

        /** For instrumented tests (StoreContract against Room): nothing touches disk. */
        fun inMemory(context: Context): PlannerStoreDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, PlannerStoreDatabase::class.java).build()
    }
}
