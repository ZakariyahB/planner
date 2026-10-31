package app.planner.di

import android.content.Context
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.CheckInRescheduler
import app.planner.engine.ports.ExecutionLogStore
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.ProposalPort
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.SnapshotStore
import app.planner.engine.ports.TaskStore
import app.planner.engine.schedule.Engine
import app.planner.gcal.AuthTracker
import app.planner.gcal.CalendarGateway
import app.planner.gcal.CalendarWriter
import app.planner.gcal.GcalAuthorizer
import app.planner.gcal.GcalRefresh
import app.planner.gcal.IdSource
import app.planner.gcal.PlannerService
import app.planner.gcal.UrlConnectionTransport
import app.planner.gcal.ZoneClock
import app.planner.gcal.rest.GoogleCalendarRestGateway
import app.planner.gcal.sync.CalendarSync
import app.planner.gcal.write.DefaultCalendarWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Provider
import javax.inject.Singleton

// Owner: Stream B. Google Calendar over REST v3, Stream A's engine, the v2 stores, and the only CalendarWriter.
// CheckInRescheduler is taken through a Provider: Stream E's scheduler reads PlannerQueries, so a direct
// dependency would be a cycle.
@Module
@InstallIn(SingletonComponent::class)
object CalendarBindings {
    @Provides @Singleton fun authTracker(): AuthTracker = AuthTracker()

    @Provides @Singleton
    fun authorizer(@ApplicationContext context: Context, auth: AuthTracker): GcalAuthorizer = GcalAuthorizer(context, auth)

    @Provides @Singleton fun zoneClock(settings: SettingsStore): ZoneClock = ZoneClock(settings)

    @Provides @Singleton
    fun gateway(authorizer: GcalAuthorizer, settings: SettingsStore, clock: ZoneClock): CalendarGateway =
        GoogleCalendarRestGateway(
            http = UrlConnectionTransport(),
            tokens = authorizer,
            settings = { settings.current() },
            today = { clock.now().toLocalDate() },
        )

    @Provides @Singleton
    fun calendarSync(
        @ApplicationContext context: Context,
        gateway: CalendarGateway,
        snapshots: SnapshotStore,
        auth: AuthTracker,
        rescheduler: Provider<CheckInRescheduler>,
        clock: ZoneClock,
    ): CalendarSync = CalendarSync(
        gateway = gateway,
        snapshots = snapshots,
        auth = auth,
        rescheduler = { rescheduler.get().reschedule() },
        now = { clock.now() },
        clock = { Instant.now() },
    ).also { GcalRefresh.install(context, it) }

    @Provides @Singleton
    fun plannerService(
        gateway: CalendarGateway,
        sync: CalendarSync,
        tasks: TaskStore,
        changeSets: ChangeSetStore,
        snapshots: SnapshotStore,
        settings: SettingsStore,
        logs: ExecutionLogStore,
        auth: AuthTracker,
        clock: ZoneClock,
    ): PlannerService = PlannerService(
        engine = Engine.planning,
        prayers = Engine.prayers,
        placer = Engine.placer,
        sync = sync,
        tasks = tasks,
        changeSets = changeSets,
        snapshots = snapshots,
        settings = settings,
        logs = logs,
        auth = auth,
        now = { clock.now() },
        clock = { Instant.now() },
        ids = IdSource.Random,
        fetch = { ref -> runCatching { gateway.event(ref) }.getOrNull() },
    )

    @Provides @Singleton fun plannerQueries(service: PlannerService): PlannerQueries = service

    @Provides @Singleton fun proposalPort(service: PlannerService): ProposalPort = service

    @Provides @Singleton
    fun calendarWriter(
        gateway: CalendarGateway,
        changeSets: ChangeSetStore,
        snapshots: SnapshotStore,
        tasks: TaskStore,
        rescheduler: Provider<CheckInRescheduler>,
        auth: AuthTracker,
        sync: CalendarSync,
    ): CalendarWriter = DefaultCalendarWriter(
        gateway = gateway,
        changeSets = changeSets,
        snapshots = snapshots,
        tasks = tasks,
        rescheduler = { rescheduler.get().reschedule() },
        auth = auth,
        clock = { Instant.now() },
        ids = IdSource.Random,
        afterWrite = { sync.refresh() },
    )
}
