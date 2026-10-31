package app.planner.assistant

import app.planner.assistant.llm.ContentBlock
import app.planner.assistant.llm.LlmClient
import app.planner.assistant.llm.LlmRequest
import app.planner.assistant.llm.LlmResponse
import app.planner.assistant.llm.Message
import app.planner.assistant.llm.Role
import app.planner.assistant.prompt.PromptBundle
import app.planner.engine.changeset.Change
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventTime
import app.planner.engine.ports.ChangeDraft
import app.planner.engine.ports.ImportReport
import app.planner.engine.ports.ProposalOutcome
import app.planner.engine.ports.ProposalPort
import app.planner.engine.ports.ReplanReason
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Gradle runs the tests from the module directory, android/assistant. */
val promptDir = File("../../docs/prompts")

fun loadPrompts(): PromptBundle = PromptBundle.load { File(promptDir, it).readText() }

/** Monday 2 November 2026, 08:00: the reference week's first morning. */
val MONDAY_MORNING: LocalDateTime = LocalDateTime.of(2026, 11, 2, 8, 0)

fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/**
 * A fake model. Each call gets the request (so a step can read the previous tool results, as the real model would)
 * and returns the next scripted reply. Running out of script fails the test.
 */
class ScriptedLlm(private vararg val steps: (LlmRequest) -> LlmResponse) : LlmClient {
    val requests = mutableListOf<LlmRequest>()

    override suspend fun send(request: LlmRequest): LlmResponse {
        requests += request
        val step = steps.getOrNull(requests.size - 1) ?: error("The model was called ${requests.size} times; the script has ${steps.size} steps")
        return step(request)
    }
}

private var toolUseCounter = 0

fun toolUse(name: String, input: String, text: String? = null): LlmResponse = LlmResponse(
    Message(Role.ASSISTANT, listOfNotNull(text?.let { ContentBlock.Text(it) }, ContentBlock.ToolUse("toolu_${++toolUseCounter}", name, json(input)))),
    stopReason = "tool_use",
)

fun reply(text: String): LlmResponse = LlmResponse(Message(Role.ASSISTANT, listOf(ContentBlock.Text(text))), stopReason = "end_turn")

/** The tool results the model was sent most recently, parsed. */
fun LlmRequest.lastToolResults(): List<JsonObject> =
    messages.last().content.filterIsInstance<ContentBlock.ToolResult>().map { json(it.content) }

/** A ProposalPort that records every call exactly and answers with a small proposal (or [answer]). */
class RecordingProposalPort(var answer: ((String) -> ProposalOutcome)? = null) : ProposalPort {
    sealed interface Call {
        data class Propose(val drafts: List<ChangeDraft>, val source: ChangeSource, val summary: String) : Call
        data class Replan(val reason: ReplanReason, val source: ChangeSource) : Call
        data class PlanDays(val days: List<LocalDate>, val source: ChangeSource) : Call
        data class Rollover(val target: LocalDate, val source: ChangeSource) : Call
        data class Import(val from: LocalDate, val to: LocalDate) : Call
    }

    val calls = mutableListOf<Call>()
    private var n = 0

    private fun outcome(summary: String): ProposalOutcome {
        answer?.let { return it(summary) }
        val day = LocalDate.of(2026, 11, 5)
        val cs = ChangeSet(
            id = "cs-${++n}",
            source = ChangeSource.ASSISTANT,
            summary = summary,
            changes = listOf(
                Change(
                    "c1", ChangeType.ADD,
                    after = EventDraft("cal-personal", "Planned", EventTime.Timed(day.atTime(14, 0), day.atTime(15, 15))),
                    note = "Includes 15 min for Asr",
                ),
            ),
            createdAt = Instant.EPOCH,
        )
        return ProposalOutcome.Proposed(cs, emptyList())
    }

    override suspend fun propose(drafts: List<ChangeDraft>, source: ChangeSource, summary: String): ProposalOutcome {
        calls += Call.Propose(drafts, source, summary)
        return outcome(summary)
    }

    override suspend fun replan(reason: ReplanReason, source: ChangeSource): ProposalOutcome {
        calls += Call.Replan(reason, source)
        return outcome("Replan")
    }

    override suspend fun planDays(days: List<LocalDate>, source: ChangeSource): ProposalOutcome {
        calls += Call.PlanDays(days, source)
        return outcome("Plan days")
    }

    override suspend fun rollover(targetWeekStart: LocalDate, source: ChangeSource): ProposalOutcome {
        calls += Call.Rollover(targetWeekStart, source)
        return outcome("Rollover")
    }

    override suspend fun importTemplate(from: LocalDate, toExclusive: LocalDate): ImportReport {
        calls += Call.Import(from, toExclusive)
        return ImportReport(0, 0, 0)
    }
}
