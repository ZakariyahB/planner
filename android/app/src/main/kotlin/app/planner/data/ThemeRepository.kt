package app.planner.data

import app.planner.theme.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Whether the app is light, dark or follows the phone, kept in settings. */
@Singleton
class ThemeRepository @Inject constructor(private val settings: SettingsRepository) {
    val mode: Flow<ThemeMode> = settings.observe(KEY).map(ThemeMode::decode)

    suspend fun current(): ThemeMode = ThemeMode.decode(settings.get(KEY))

    suspend fun save(mode: ThemeMode) = settings.put(KEY, mode.name)

    private companion object {
        const val KEY = "theme_mode"
    }
}
