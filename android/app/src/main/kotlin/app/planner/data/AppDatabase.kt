package app.planner.data

import android.content.Context
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
    entities = [SettingEntity::class, TaskEntity::class, CalendarEventEntity::class],
    version = 2,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
    abstract fun tasks(): TaskDao
    abstract fun calendarEvents(): CalendarEventDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    // v1 (M0) held only an empty settings table, so dropping it loses nothing.
    // From v2 on, schema changes need real Migrations because the DB holds the user's data.
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "planner.db")
            .fallbackToDestructiveMigrationFrom(1)
            .build()

    @Provides fun settingsDao(db: AppDatabase): SettingsDao = db.settings()
    @Provides fun taskDao(db: AppDatabase): TaskDao = db.tasks()
    @Provides fun calendarEventDao(db: AppDatabase): CalendarEventDao = db.calendarEvents()

    @Provides fun clock(): Clock = Clock.systemDefaultZone()
}
