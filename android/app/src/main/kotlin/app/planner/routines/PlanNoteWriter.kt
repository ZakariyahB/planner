package app.planner.routines

import app.planner.ai.AiAssistant
import app.planner.ai.Purpose
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The optional AI sentence on the 23:30 notification. Anything that goes wrong (not signed in, over the cap,
 * offline, slow) just means there is no note; the notification is built from deterministic text either way.
 */
@Singleton
class PlanNoteWriter @Inject constructor(
    private val assistant: AiAssistant,
    private val routines: RoutineSettings,
) {
    suspend fun write(date: LocalDate, rows: List<PlanRow>, forecast: ForecastNote?): String? {
        val reply = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                assistant.ask(emptyList(), PlanNotePrompt.build(date, rows, forecast), Purpose.SCHEDULED, readOnly = true).reply
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val note = reply?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_CHARS) ?: return null
        routines.savePlanNote(date, note)
        return note
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val MAX_CHARS = 400
    }
}
