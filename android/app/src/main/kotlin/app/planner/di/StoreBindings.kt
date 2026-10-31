package app.planner.di

import android.content.Context
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.ExecutionLogStore
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.SnapshotStore
import app.planner.engine.ports.TaskStore
import app.planner.store.PlannerStoreDatabase
import app.planner.store.RoomChangeSetStore
import app.planner.store.RoomExecutionLogStore
import app.planner.store.RoomSettingsStore
import app.planner.store.RoomSnapshotStore
import app.planner.store.RoomTaskStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Owner: Stream F. The storage ports backed by the v2 Room database (planner_v2.db), separate from the legacy
// AppDatabase. Previews and tests keep using the in-memory fakes in app.planner.engine.fakes.
@Module
@InstallIn(SingletonComponent::class)
object StoreBindings {
    @Provides @Singleton
    fun plannerStoreDatabase(@ApplicationContext context: Context): PlannerStoreDatabase = PlannerStoreDatabase.open(context)

    @Provides @Singleton fun taskStore(db: PlannerStoreDatabase): TaskStore = RoomTaskStore(db)
    @Provides @Singleton fun changeSetStore(db: PlannerStoreDatabase): ChangeSetStore = RoomChangeSetStore(db)
    @Provides @Singleton fun snapshotStore(db: PlannerStoreDatabase): SnapshotStore = RoomSnapshotStore(db)
    @Provides @Singleton fun executionLogStore(db: PlannerStoreDatabase): ExecutionLogStore = RoomExecutionLogStore(db)
    @Provides @Singleton fun settingsStore(db: PlannerStoreDatabase): SettingsStore = RoomSettingsStore(db)
}
