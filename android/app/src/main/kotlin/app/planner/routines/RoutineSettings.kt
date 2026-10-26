package app.planner.routines

import app.planner.data.SettingsRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Routine toggles and "already done today" markers, kept in the settings table as plain key-value pairs. */
@Singleton
class RoutineSettings @Inject constructor(
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    /** On unless the user switched it off. */
    suspend fun enabled(routine: Routine): Boolean = settings.get(enabledKey(routine)) != OFF

    fun observeEnabled(routine: Routine): Flow<Boolean> = settings.observe(enabledKey(routine)).map { it != OFF }

    suspend fun setEnabled(routine: Routine, on: Boolean) = settings.put(enabledKey(routine), if (on) ON else OFF)

    suspend fun sentOn(routine: Routine): LocalDate? = settings.get(sentKey(routine))?.let(::parseDate)

    fun observeSentOn(routine: Routine): Flow<LocalDate?> = settings.observe(sentKey(routine)).map { it?.let(::parseDate) }

    suspend fun markSent(routine: Routine, date: LocalDate) = settings.put(sentKey(routine), date.toString())

    /** The day whose plan he approved on the 23:30 sheet (the plan's own date, i.e. tomorrow at that time). */
    fun observePlanApprovedFor(): Flow<LocalDate?> = settings.observe(KEY_PLAN_APPROVED).map { it?.let(::parseDate) }

    suspend fun markPlanApproved(date: LocalDate) = settings.put(KEY_PLAN_APPROVED, date.toString())

    /** The AI's short note on tomorrow's plan, kept with the day it is about. */
    suspend fun savePlanNote(date: LocalDate, note: String) = settings.put(KEY_PLAN_NOTE, "$date|$note")

    suspend fun planNote(date: LocalDate): String? {
        val parts = settings.get(KEY_PLAN_NOTE)?.split('|', limit = 2) ?: return null
        return parts.getOrNull(1)?.takeIf { parts[0] == date.toString() && it.isNotBlank() }
    }

    /** Mondays of the weeks whose Sunday review he has opened. */
    val reviewsOpened: Flow<Set<LocalDate>> = settings.observe(KEY_REVIEWS_OPENED).map(::parseDates)

    suspend fun markReviewOpened(week: LocalDate) = addDate(KEY_REVIEWS_OPENED, week)

    /** Mondays of the weeks already generated from the template, by the review or the Monday catch-up. */
    suspend fun generatedWeeks(): Set<LocalDate> = parseDates(settings.get(KEY_WEEKS_GENERATED))

    suspend fun markWeekGenerated(week: LocalDate) = addDate(KEY_WEEKS_GENERATED, week)

    /** When the routines were first switched on; a Sunday review before that is not offered. */
    val startedAt: Flow<Instant?> = settings.observe(KEY_STARTED).map { it?.toLongOrNull()?.let(Instant::ofEpochMilli) }

    suspend fun ensureStarted() {
        if (settings.get(KEY_STARTED) == null) settings.put(KEY_STARTED, clock.millis().toString())
    }

    suspend fun notificationPromptSeen(): Boolean = settings.get(KEY_PROMPT_SEEN) == ON

    fun observeNotificationPromptSeen(): Flow<Boolean> = settings.observe(KEY_PROMPT_SEEN).map { it == ON }

    suspend fun markNotificationPromptSeen() = settings.put(KEY_PROMPT_SEEN, ON)

    private suspend fun addDate(key: String, date: LocalDate) {
        val kept = (parseDates(settings.get(key)) + date).sorted().takeLast(MAX_KEPT)
        settings.put(key, kept.joinToString(","))
    }

    private fun parseDates(text: String?): Set<LocalDate> =
        text.orEmpty().split(',').mapNotNull { parseDate(it) }.toSet()

    private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()

    private fun enabledKey(routine: Routine) = "routine_enabled_${routine.name.lowercase()}"

    private fun sentKey(routine: Routine) = "routine_sent_${routine.name.lowercase()}"

    private companion object {
        const val ON = "on"
        const val OFF = "off"
        const val MAX_KEPT = 12
        const val KEY_PLAN_APPROVED = "routine_plan_approved_for"
        const val KEY_PLAN_NOTE = "routine_plan_note"
        const val KEY_REVIEWS_OPENED = "routine_reviews_opened"
        const val KEY_WEEKS_GENERATED = "routine_weeks_generated"
        const val KEY_STARTED = "routine_started_at"
        const val KEY_PROMPT_SEEN = "routine_notification_prompt_seen"
    }
}
