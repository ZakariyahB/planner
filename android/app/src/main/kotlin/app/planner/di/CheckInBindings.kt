package app.planner.di

import app.planner.checkin.CheckInScheduler
import app.planner.engine.ports.CheckInRescheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Owner: Stream E. The real rescheduler: exact alarms for today's and tomorrow's check-ins and the next prompts.
// CheckInScheduler takes PlannerQueries through a Provider, so Stream B's planner service may inject
// CheckInRescheduler without a dependency cycle.
@Module
@InstallIn(SingletonComponent::class)
object CheckInBindings {
    @Provides @Singleton fun rescheduler(scheduler: CheckInScheduler): CheckInRescheduler = scheduler
}
