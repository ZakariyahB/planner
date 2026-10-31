package app.planner.di

import app.planner.engine.ports.CheckInRescheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Owner: Stream E. Stream 0 binds a no-op so every branch builds.
@Module
@InstallIn(SingletonComponent::class)
object CheckInBindings {
    @Provides @Singleton fun rescheduler(): CheckInRescheduler = CheckInRescheduler { }
}
