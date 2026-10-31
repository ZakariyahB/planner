package app.planner.di

import app.planner.engine.changeset.AppliedRecord
import app.planner.engine.changeset.ChangeResult
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.fakes.FakePlannerQueries
import app.planner.engine.fakes.FakeProposalPort
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.ProposalPort
import app.planner.gcal.ApprovedChangeSet
import app.planner.gcal.CalendarWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import javax.inject.Singleton

// Owner: Stream B. Stream 0 binds fakes (the reference week shifted to this week) so every branch builds and runs.
@Module
@InstallIn(SingletonComponent::class)
object CalendarBindings {
    @Provides @Singleton
    fun plannerQueries(): PlannerQueries = FakePlannerQueries(
        now = { LocalDateTime.now() },
        weekStart = java.time.LocalDate.now().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)),
    )

    @Provides @Singleton
    fun proposalPort(store: ChangeSetStore): ProposalPort = FakeProposalPort(store)

    @Provides @Singleton
    fun calendarWriter(store: ChangeSetStore): CalendarWriter = object : CalendarWriter {
        override suspend fun apply(approved: ApprovedChangeSet): AppliedRecord =
            AppliedRecord(
                approved.changeSet.id, Instant.now(),
                approved.approved.map { ChangeResult(it.id, ChangeStatus.VERIFIED, it.target?.ref) },
                inverse = emptyList(),
            ).also { store.saveApplied(it); store.save(approved.changeSet.copy(status = ChangeStatus.APPLIED)) }

        override suspend fun undo(record: AppliedRecord): AppliedRecord = record.copy(undone = true).also { store.saveApplied(it) }
    }
}
