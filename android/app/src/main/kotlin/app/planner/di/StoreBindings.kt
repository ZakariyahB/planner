package app.planner.di

import app.planner.engine.fakes.InMemoryChangeSetStore
import app.planner.engine.fakes.InMemoryExecutionLogStore
import app.planner.engine.fakes.InMemorySettingsStore
import app.planner.engine.fakes.InMemorySnapshotStore
import app.planner.engine.fakes.InMemoryTaskStore
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.ExecutionLogStore
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.SnapshotStore
import app.planner.engine.ports.TaskStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Owner: Stream F. Stream 0 binds in-memory fakes so every branch builds; Stream F replaces these with Room stores.
@Module
@InstallIn(SingletonComponent::class)
object StoreBindings {
    @Provides @Singleton fun taskStore(): TaskStore = InMemoryTaskStore()
    @Provides @Singleton fun changeSetStore(): ChangeSetStore = InMemoryChangeSetStore()
    @Provides @Singleton fun snapshotStore(): SnapshotStore = InMemorySnapshotStore()
    @Provides @Singleton fun executionLogStore(): ExecutionLogStore = InMemoryExecutionLogStore()
    @Provides @Singleton fun settingsStore(): SettingsStore = InMemorySettingsStore()
}
