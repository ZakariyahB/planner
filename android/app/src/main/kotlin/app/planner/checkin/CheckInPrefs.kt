package app.planner.checkin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Device-local bookkeeping for check-ins: which alarms the last reschedule set (to cancel the ones that are no
 * longer planned), whether they were exact, and whether the battery explainer was seen. Losing it is harmless:
 * the next reschedule sets everything again.
 */
@Singleton
class CheckInPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    fun alarms(): Map<String, String> =
        runCatching { Json.decodeFromString(mapSerializer, prefs.getString(KEY_ALARMS, null) ?: return emptyMap()) }.getOrDefault(emptyMap())

    fun saveAlarms(alarms: Map<String, String>) {
        prefs.edit().putString(KEY_ALARMS, Json.encodeToString(mapSerializer, alarms)).apply()
    }

    /** False when the last reschedule had to fall back to inexact alarms (no exact-alarm permission). */
    var lastExact: Boolean
        get() = prefs.getBoolean(KEY_EXACT, true)
        set(value) = prefs.edit().putBoolean(KEY_EXACT, value).apply()

    var batteryExplainerSeen: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_SEEN, value).apply()

    private companion object {
        const val FILE = "checkins"
        const val KEY_ALARMS = "alarms"
        const val KEY_EXACT = "last_exact"
        const val KEY_BATTERY_SEEN = "battery_explainer_seen"
    }
}
