package app.planner.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Database(
    entities = [
        SettingEntity::class,
        TaskEntity::class,
        CalendarEventEntity::class,
        BlockEntity::class,
        ChangeLogEntity::class,
        SleepEntity::class,
        ReadinessEntity::class,
    ],
    version = 4,
    autoMigrations = [AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
    abstract fun tasks(): TaskDao
    abstract fun calendarEvents(): CalendarEventDao
    abstract fun blocks(): BlockDao
    abstract fun changeLog(): ChangeLogDao
    abstract fun sleep(): SleepDao
    abstract fun readiness(): ReadinessDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    // v1 (M0) held only an empty settings table, so dropping it loses nothing.
    // From v2 on the DB holds the user's data: every schema change needs a Migration (see schemas/).
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "planner.db")
            .fallbackToDestructiveMigrationFrom(1)
            .build()

    @Provides fun settingsDao(db: AppDatabase): SettingsDao = db.settings()
    @Provides fun taskDao(db: AppDatabase): TaskDao = db.tasks()
    @Provides fun calendarEventDao(db: AppDatabase): CalendarEventDao = db.calendarEvents()
    @Provides fun blockDao(db: AppDatabase): BlockDao = db.blocks()
    @Provides fun changeLogDao(db: AppDatabase): ChangeLogDao = db.changeLog()
    @Provides fun sleepDao(db: AppDatabase): SleepDao = db.sleep()
    @Provides fun readinessDao(db: AppDatabase): ReadinessDao = db.readiness()

    @Provides fun clock(): Clock = Clock.systemDefaultZone()
}
