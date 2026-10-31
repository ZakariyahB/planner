# Stream C: assistant, notes for the final docs

## What was built

- `:assistant` (pure Kotlin, depends on `:engine` only):
  - `llm/`: Messages API types, `MessagesJson` codec, `LlmClient`, `LlmException`/`LlmFailure`, `WorkerErrors`.
  - `ToolLoop`: send, run the tools asked for, send all results back in one user message, repeat (8 rounds max).
  - `tools/`: the six tools (`get_week`, `get_tasks`, `get_prayer_anchors`, `find_events`, `propose_changes`, `replan`) over `PlannerQueries`, `TaskStore` and `ProposalPort`. There is no apply tool. Inputs are checked strictly; a bad one goes back to the model as an error and never reaches the engine.
  - `prompt/`: `PromptBundle` (loads `system-v2.md`, `calendar-categories.md`, `tools-v2.json`) and `ContextSummary` (the compact week, backlog and settings).
  - `AssistantService` implements `AssistantPort`; `conversation/` has the `ConversationStore` port, an in-memory store, a JSON codec and `FileConversationStore`.
- `docs/prompts/system-v2.md` and `tools-v2.json`. The legacy `system.md`/`tools.json` are untouched.
- `:app`: `assistant/WorkerAssistantClient.kt` (the Worker over HTTP, reusing the legacy `IdTokenProvider`, `AiSpendRepository` and `UsageReport`) and `di/AssistantBindings.kt`.
- `worker/`: model allow-list header, current prices, larger `max_tokens` limit, server-side refusal fallback.

## How the propose-only rule is enforced

1. `:assistant` cannot see `:calendar` (`AssistantIsolationTest`), so `CalendarWriter` and `ApprovalGate` do not exist for it.
2. The tool list has no apply, approve or write tool (`PromptFilesTest` checks the names). An unknown tool name returns an error result.
3. `propose_changes` and `replan` call only `ProposalPort` with `ChangeSource.ASSISTANT`. The returned proposal ids become `AssistantReply.proposalIds` and `ChatTurn.proposalId`, so the chat can show the approval card inline.
4. `ExampleIntentsTest` runs each newplan 9 intent end to end with a scripted model and asserts exactly one `ProposalPort` call and nothing else:
   - "halaqa ran over by 1h": `find_events("Halaqa")`, then `replan(Overrun(ref, 60))`
   - "remove Quran class": `find_events`, then `propose([RemoveEvent(ref, THIS_INSTANCE)])`
   - "push tomorrow's lectures to Thursday": `find_events("Lecture", tomorrow)`, then one `MoveEvent(ref, thursday)` per candidate
   - "add 2h internship apps Sunday": `propose([AddTask("Internship apps", PERSONAL, 120, sunday)])`
   - "copy template into next week": `rollover(nextMonday)`

## Tool mapping (for MANUAL.md / the master)

| Tool | Port call |
|---|---|
| `propose_changes` action `add` / `schedule_task` / `move` / `edit` / `remove` | `ProposalPort.propose([AddTask / ScheduleTask / MoveEvent / EditEvent / RemoveEvent])` |
| `replan` reason `overrun` / `stopped_early` / `other` | `ProposalPort.replan(Overrun / StoppedEarly / Free)` |
| `replan` reason `plan_days` | `ProposalPort.planDays(days)` |
| `replan` reason `rollover` | `ProposalPort.rollover(targetWeekStart)` (must be a Monday) |

`ReplanReason.UserEdited` is not exposed to the model; sync produces it.

## Differences from the plan, and why

- **The week summary goes in each user message, not the system prompt.** The plan puts "a compact summary of the current week, backlog and settings" in the system prompt. Current models (Sonnet 5.5, Opus 5.5) bind their thinking blocks to the exact earlier request (system prompt, tools and messages); on accounts created after 31 August 2026, a replayed history whose system prompt changed is rejected with a 400. So the system prompt is frozen per conversation and each user message starts with a fresh `[App context ...]` text block. This also keeps the prompt cache warm.
- **The history is append-only.** Thinking, redacted thinking and other blocks the app does not use are kept byte for byte (`ContentBlock.Opaque`). When the saved history goes over about 120k characters, the model's history starts afresh (the chat on screen is kept, with a note) rather than being trimmed.
- **There is a new client instead of the legacy `WorkerLlmClient`.** The legacy codec drops thinking blocks, which would break the rule above, and it cannot name a model. `WorkerAssistantClient` keeps the same HTTP style, token and spend reporting.
- **There is no streaming.** The Worker forces `stream: false`, so v1 is non-streamed, as the stream allows.
- **`rollover` and `plan_days` sit under `replan`.** This keeps exactly the six tools the plan names.

## Claude API choices (checked against the claude-api skill, October 2026)

- The model comes from `Settings.assistantModel` (default `claude-sonnet-5-5`) and is sent in `X-Planner-Model`. The Worker accepts only models it can price (`claude-sonnet-5-5`, `claude-haiku-5-5`, `claude-opus-5-5`, `claude-sonnet-5`) or the `MODEL_CHAT`/`MODEL_SCHEDULED` overrides, and returns 400 "unknown model" for anything else (the chat says the Settings model is not allowed).
- No `thinking` field is sent: Sonnet 5.5 thinks adaptively by default, and `disabled` would be a 400. No forced `tool_choice`, which is also a 400 on 5.5 models. `output_config.effort` is `medium`.
- `max_tokens` is 4096 from the app. The Worker limit went from 2048 to 8192 because thinking counts against it.
- Caching: a breakpoint on the frozen system prompt, which also covers the tools, plus top-level `cache_control` for the conversation.
- `stop_reason: "refusal"`: nothing from the turn is kept and the user gets a short reply. `max_tokens` while calling a tool: that call is not run, and the model is told to retry.
- **Server-side refusal fallback is turned on in the Worker** for `claude-sonnet-5-5` and `claude-opus-5-5` (`anthropic-beta: server-side-fallback-2026-07-01`, `fallbacks: "default"`). Set `DISABLE_FALLBACKS = "1"` in `wrangler.toml` to turn it off. The client drops the thinking and tool calls that come before a `fallback` block, as documented. A reply is priced at the model that answered.
- Worker prices were updated to the current list: Sonnet 5.5 $2/$10, Haiku 5.5 $0.10/$0.50, Opus 5.5 $4/$20 per million tokens. The old table had Sonnet at $3/$15 and Haiku at $1/$5. The cap is still enforced from these estimates.
- I did not set `thinking.block_binding.prefix_mismatch_behavior: "drop_block"` (beta), to keep the request shape minimal. If a 400 about thinking blocks ever appears, that is the switch to add, or clear the chat.

## Setup

- Redeploy the Worker (`cd worker && npm ci && npm test && npx wrangler deploy`). The new app needs the `X-Planner-Model` support; the legacy app works with either Worker version.
- Nothing new in `local.properties`: the app still uses `WORKER_URL` and `GOOGLE_WEB_CLIENT_ID`.
- The prompts ship from `docs/prompts` as assets (existing `app/build.gradle.kts` rule).

## What to test by hand

1. Sign in, open Chat, send "add 2h internship apps Sunday". A proposal card should appear in the chat, and nothing should change in Google Calendar until you approve it.
2. "remove Quran class": the reply should name the exact event and wait for approval. With two matching events, it should ask which.
3. "halaqa ran over by 1h" and "push tomorrow's lectures to Thursday": the proposals should show engine times, not times the model made up.
4. "copy template into next week": a rollover proposal for next Monday's week.
5. Ask it to "just apply it": it should refuse and point to the card.
6. Kill and reopen the app: the conversation is still there. Clear the chat, then send a message: it starts fresh.
7. Turn off the network: the chat shows "No connection to the assistant", and the app keeps working.
8. Set a model the Worker does not know in Settings: the chat shows the "isn't one the server allows" note.

## Open items

- The legacy `app/.../ai/*` assistant still exists and still uses `system.md`/`tools.json`. `WorkerAssistantClient` depends on the legacy `IdTokenProvider`, `AiSpendRepository` (app) and `UsageReport`/`PlainErrors` (core), so keep those when the legacy code is deleted, or move them.
- Lifestyle coaching uses only what the context and tools give; sleep data is not wired in yet. Stream G's summary function could be added to `ContextSummary` later.
