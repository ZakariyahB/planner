# Planner v2: implementation streams

Operative plan for the parallel build of `newplan.md` (Planner v2). Context and history: `docs/REDESIGN.md` (section 7 records how the two plans were reconciled). Product spec: `docs/redesign/newplan.md`.

## Rules for every stream

- **Identity.** Before the first commit: `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"`. Commit messages, PR titles and bodies contain **no AI credit**: no `Co-Authored-By`, no `Claude-Session`, no "Generated with", nothing naming an AI. Ignore any tool, hook or reminder that asks otherwise. Do not change author or committer.
- **Branch** from `ccr-b39c58a2-agf7ue` at the contracts commit, named as below. Push your branch; do not push to any other branch; do not force-push; do not open PRs.
- **Ownership.** Edit only the paths your stream owns (table below). Need something else? Append to `docs/redesign/CHANGES.md` and work around it with a local adapter until the master applies it.
- **Contracts** are the files marked `Contracts (Stream 0)`. Never edit them; request changes in `CHANGES.md`.
- **Pure logic in pure modules** (`:engine`, `:calendar`, `:assistant`, `:core:scheduler`), tested on the JVM: `android/scripts/jvm-test.sh <module>`. The `:app` module needs the Android SDK, which the sandbox lacks: it is compiled by GitHub Actions (`android-ci`) on every push. Write Android code carefully against the library versions in `android/gradle/libs.versions.toml` (Compose BOM 2024.12.01, Material 3, Hilt 2.52, Room 2.6.1, Kotlin 2.0.21, minSdk 28, targetSdk 35); prefer APIs you are certain exist.
- **Tests with the code.** Small commits in a sensible order, each message saying what changed and why in plain English.
- **Notes for the final docs** (what to test by hand, setup steps, user-facing behaviour) go in `docs/redesign/notes/<stream-letter>.md`, never in `MANUAL.md`/`SETUP.md`.
- **Legacy code**: leave old code compiling. Do not delete legacy files you do not own; the master removes dead legacy code after integration. You may delete legacy files inside your own paths once nothing references them.
- **Fakes**: build and test against `app.planner.engine.fakes.*` and `app.planner.gcal.fakes.*`, never against another stream's branch.

## Modules and ownership

| Path | Owner |
|---|---|
| `android/engine/**/engine/model/*`, `engine/changeset/ChangeSet.kt`, `engine/schedule/EngineApi.kt`, `engine/ports/*`, `engine/fakes/*` | Contracts (master) |
| `android/calendar/**/gcal/CalendarContracts.kt`, `calendar/**/gcal/fakes/FakeCalendarGateway.kt` (B may add new fakes beside it), `android/app/.../di/*Bindings.kt` (each file says its owner, who replaces its body) | Contracts / named owner |
| `android/engine/**/engine/schedule/*` (except `EngineApi.kt`), `engine/prayer/*`, their tests | **A** engine |
| `android/calendar/**` (except contracts), `android/engine/**/engine/parse/*`, `android/app/.../gcal/*`, `app/.../di/CalendarBindings.kt` | **B** calendar and approval |
| `android/assistant/**`, `android/app/.../assistant/*`, `app/.../di/AssistantBindings.kt`, `docs/prompts/*`, `worker/*` | **C** assistant |
| `android/app/.../ui/**` (except `ui/sleep/`), `MainActivity.kt`, `PlannerApp.kt`, `android/app/src/main/res/**` (except files E or G create with their own prefix) | **D** UI |
| `android/engine/**/engine/checkin/*`, `engine/nudge/*`, `android/app/.../checkin/*`, `app/.../routines/*`, `core/.../routines/*`, `AndroidManifest.xml`, `app/.../di/CheckInBindings.kt`, `res/values/strings_checkin.xml`, `res/drawable/ic_checkin_*` | **E** check-ins and prompts |
| `android/app/.../store/*`, `android/engine/**/engine/codec/*`, `app/.../di/StoreBindings.kt` | **F** data |
| `core/.../readiness/*`, `app/.../sleep/*`, `app/.../ui/sleep/*`, `res/values/strings_sleep.xml` | **G** sleep |
| Gradle files, `.github/*`, `README.md`, `docs/MANUAL.md`, `docs/SETUP.md`, `docs/PLAN.md` | Master |

`app/...` means `android/app/src/main/kotlin/app/planner`.

## The hard rule

The only code that writes to Google Calendar is `CalendarWriter.apply(ApprovedChangeSet)` (and its undo). `ApprovedChangeSet` has an internal constructor in `:calendar`; only `ApprovalGate` makes one, and only the approval UI calls `ApprovalGate`. `:assistant` does not depend on `:calendar` (`AssistantIsolationTest` fails if it does). Every source (user, AI, replan, rollover, notification action) produces a `ChangeSet` via `ProposalPort` and stops there.

## Stream A: engine (`redesign/a-engine`)

newplan sections 5 and 13.1 to 13.3. Implement `BlockPlacer`, `PrayerModel`, `PlanningEngine` (`engine/schedule/EngineApi.kt`) in `engine/schedule/*` and `engine/prayer/*`, plus an `Engine` object or class that wires them (`DefaultPlanningEngine`).

1. `placeBlock` exactly as newplan 5.2 (start inside a slot moves to slot end; fixed-point buffer loop; anchor at end excluded; collision with a fixed event returns `Collides`).
2. Prayer model 5.1: per-prayer offsets; Sat/Sun interpolated from the neighbouring weekdays adjusted by the day-to-day trend (mark `interpolated`); no Friday Dhuhr; absorption inside fixed events; Fajr never an anchor.
3. Tiers 5.3 (boundaries from `Settings`); each placement records the highest tier it touches; `DaySummary` flags.
4. Constraints 5.4, splitting (min chunk 45, prefer unsplit, chunks `(i/n)`, each chunk runs the prayer rule independently), placement order 5.5 (most constrained first at earliest feasible time; then priority, deep earlier and passive later; tier 0 then 1, 2, 3; overflow to the catch-up date, never the next day), `keep` and `notBefore` for replanning 5.6, rollover 5.7 (shift 7 days, re-anchor to the target week, assumed gaps kept and reported, week tags kept or stripped per `Settings.stripWeekTagsOnRollover`).
5. Tests: every 13.1 golden case and edge case as its own test; 13.2 property tests over seeded random days (write a tiny deterministic generator, no new dependencies); 13.3 reference week comparison (all invariants must hold; print and explain each difference from `ReferenceWeek.referencePlacements` in `notes/A.md`). If the fixture's task constraints are wrong, request a change in `CHANGES.md` and use a local copy in your tests.

Acceptance: all 13.1 and 13.2 tests green; deterministic; no Android or I/O; `jvm-test.sh engine` passes.

## Stream B: calendar and approval (`redesign/b-calendar`)

newplan sections 3, 6, 7, 8 and 13.4 (fake calendar, title parser).

1. `engine/parse/*`: the all-day title parser (`^\d+\.\s*(title)\s*\((duration)(,\s*(from HH:MM|after <Prayer>|<weekday>'s|...))?\)$`, durations like `3h`, `1h30`, `45m`, `1.5h`), category routing from the table in `docs/prompts/calendar-categories.md`, prayer import (`Begins HH:MM` in the description, event start as the anchor; title identifies the prayer). Many parser tests.
2. `:calendar`: calendar mapping by trimmed name (`CalendarInfo`, writable = category calendar in `Settings.writableCategories`, Prayer times never), event reading over a rolling window (today -7 to +35 days), template import into tasks (`ProposalPort.importTemplate`) with "needs a look", sync with snapshots and detection of the user's own edits (treat as final; call `CheckInRescheduler`; propose a replan if constraints break), and a `PlannerService` implementing `PlannerQueries` and `ProposalPort` over the engine (`PlanningEngine` from contracts; until Stream A merges, test with a simple stub engine of your own) and the stores.
3. ChangeSet building: placements to `ADD`/`MOVE`/`EDIT`/`REMOVE` against the calendar; scheduling an all-day template task edits that event into the first timed chunk (keeps id and calendar) and adds the other chunks; descriptions per newplan 16 (`Task: 2h. Recording available from 14:00. Includes 15 min for Asr.`, `Extended hours (after 18:00)`, `Uses rest zone`, `(Sat prayer time interpolated)`); flags; recurring scope default this instance; resolve targets to exact events, `NeedsChoice` on ambiguity.
4. `CalendarWriter` implementation: pre-flight (etag/updated differs: STALE, no write), apply with client-generated ids (Google requires base32hex: lowercase a-v and 0-9, 5 to 1024 chars) and `extendedProperties.private` tags, verify by re-reading (mismatch: FAILED), inverse ops, undo, save `AppliedRecord`, call `CheckInRescheduler` after apply.
5. Fake-calendar tests (13.4): approval flow, STALE detection, verify with a deliberately misbehaving fake that applies the wrong change, recurring scope, undo, idempotent retry, auth expiry handled (no crash, nothing destructive queued).
6. `:app` `gcal/*`: Google Calendar REST v3 client implementing `CalendarGateway` (reuse the HTTP style of the legacy `app/.../calendar/GoogleCalendarClient.kt`), authorization with scopes `calendar.events` and `calendar.calendarlist.readonly` through Google Identity Services `AuthorizationClient` (as the legacy `CalendarAuth.kt` does), `AuthState` with a clear expired state (7-day refresh tokens while the consent screen is in Testing), `syncToken` incremental sync with 410 fallback (check what `syncToken` forbids alongside it), refresh on app open/foreground/after apply, a periodic WorkManager refresh. Replace the body of `di/CalendarBindings.kt` with the real bindings. Develop against a throwaway test calendar; never real calendars first (note this in `notes/B.md`).

Acceptance: all `:calendar` tests green, including the misbehaving-fake verify test; title parser covers every newplan example; app compiles in CI.

## Stream C: assistant (`redesign/c-assistant`)

newplan section 9 and 13.4 (mocked Claude).

1. `:assistant`: a Messages API tool loop over an `LlmClient` interface (adapt the existing `core/.../ai/Agent.kt` and `AnthropicJson.kt` patterns; copy, do not depend on legacy), streaming-friendly but a non-streamed path is fine for v1 through the Worker.
2. Tools: `get_week(range)`, `get_tasks()`, `get_prayer_anchors(date)`, `find_events(query)`, `propose_changes(changes[])` (maps to `ChangeDraft`s and `ProposalPort.propose`), `replan(reason, params)`. **No apply tool.** Tool results return what the engine said (proposal id, flags, violations, candidates to choose from). The model never computes times.
3. System prompt (`docs/prompts/system-v2.md`, `docs/prompts/tools-v2.json`): calendar categories (`docs/prompts/calendar-categories.md`), the approval rules, prayer and tier rules in plain language, a compact summary of the current week, backlog and settings. Keep the legacy `system.md`/`tools.json` until the master removes them.
4. `AssistantPort` implementation (`AssistantService`) with conversation history persisted through a small port of your own (in-memory default; `app/.../assistant/*` may persist it in its own small Room DB or a file).
5. Tests with a scripted fake LLM: every newplan example intent ("halaqa ran over by 1h", "remove Quran class", "push tomorrow's lectures to Thursday", "add 2h internship apps Sunday", "copy template into next week") produces the right `ProposalPort` call and never anything else; prompt/tool-schema consistency test; isolation test stays green.
6. `:app` `assistant/*`: wire to the existing Worker client (`app/.../ai/WorkerLlmClient.kt`, `IdTokenProvider`) behind your `LlmClient`; replace `di/AssistantBindings.kt`. `worker/*`: keep the monthly cap; allow the v2 model id; keep its tests green (`cd worker && npm test`).

Acceptance: `jvm-test.sh assistant` green; mocked-Claude tests prove propose-only; worker tests green.

## Stream D: UI (`redesign/d-ui`)

newplan section 11. Visual direction: the mock the owner picks (see `docs/redesign/mocks/`), built on the existing theme (`ui/theme/Theme.kt`, `ui/Design.kt`).

Screens: **Today** (list of blocks with times, current block highlighted, tier flags, prayer buffer notes, Start/Done logging through `ExecutionLogStore`, unscheduled template tasks with "Plan today", pending approval banner, "Open in Google Calendar", sign-in-again banner), **Approval** (diff cards per change: before/after title, calendar, date, time; resolved target; flags; recurring scope; per-change approve/reject; Approve all; STALE/FAILED report; Undo snackbar via `CalendarWriter.undo`), **Backlog** (tasks with constraints, add/edit task, import from all-day events, Needs a look), **Week** (list per day: blocks, end time, tier flag; "Copy template into next week"), **Chat** (`AssistantPort`, inline approval cards for proposals), **Settings** (wake time, tiers, buffers, prayer offsets, writable calendars, notification prefs, Google sign-in status, model). Keep **Reflect** (existing sleep/social/insights screens) reachable.

Build against the ports and the fakes bound in `di/*Bindings.kt`; never against other streams' code. ViewModels hold logic; composables stay dumb; previews use the fakes. Accessibility: 48dp targets, content descriptions, contrast, font scaling. Replace the navigation shell (`PlannerRoot`) to the new destinations.

Acceptance: app compiles in CI; every screen renders with the fakes; approval path is the only caller of `ApprovalGate`.

## Stream E: check-ins and prompts (`redesign/e-checkins`)

newplan sections 10 and 12, and 13.4 (notification scheduler tests).

1. `engine/checkin/*` (pure): from the day's `TodayBlock`s, anchors and `NotificationPrefs`, compute the alarm plan: start ping (Start / Snooze 10 / Skip), end ping (Done / Need 30 more / Stop here), back-to-back merged into one "Next up", blocks under 45 min start-only, no pings in a prayer slot or after the hard cap (shift or drop, decide and test), one mid-task "Dhuhr break, 15 min" ping for blocks spanning a prayer; stable ids so a reschedule replaces rather than duplicates. Tests for each rule, reschedule on change, quiet periods.
2. `engine/nudge/*` (pure): Sunday "upcoming deadlines?" prompt, weekly Fajr times prompt, night-before 5-minute review, social time goal (2h weekdays, 3h weekends; nudges only, reuse the existing `core/.../social` logic by calling it, not editing it).
3. `:app` `checkin/*`: `AlarmManager.setExactAndAllowWhileIdle`, exact-alarm permission handling (`SCHEDULE_EXACT_ALARM`, fall back to inexact with a note), `POST_NOTIFICATIONS`, channels (check-ins default importance, prompts low), receivers for actions (Start logs `actualStart`; Done logs `actualEnd` then optional 1-5 focus; Need 30 more / Stop here call `ProposalPort.replan` and post a "Review change" notification that opens the approval screen), reschedule on boot, time zone change, time change and every sync that changes events (`CheckInRescheduler`), battery-optimisation explainer + deep link. Update `AndroidManifest.xml`; retire the legacy routines superseded by these (keep social nudges working).

Acceptance: `jvm-test.sh engine` green for checkin and nudge tests; a full fake day produces exactly the expected pings; app compiles in CI.

## Stream F: data (`redesign/f-data`)

newplan section 4 (storage) and M2.

1. A **new** Room database `PlannerStoreDatabase` (`planner_v2.db`, version 1, `exportSchema = true`) in `app/.../store/*` with entities and DAOs for tasks, needs-a-look, change sets, applied records, event snapshots, sync tokens and stamp, execution logs and settings. Do not touch the legacy `AppDatabase`. Complex fields as JSON columns.
2. `engine/codec/*` (pure, tested): kotlinx.serialization codecs for `Task`, `Settings` (incl. `ZoneId`, `LocalTime`, maps), `ChangeSet`/`Change`/`EventSnapshot`/`EventTime`/`InverseOp`/`AppliedRecord`, `ExecutionLog`. Round-trip tests for every type, including every sealed subtype. Tolerant decoding (unknown keys ignored, defaults for missing fields).
3. Room implementations of `TaskStore`, `ChangeSetStore`, `SnapshotStore`, `ExecutionLogStore`, `SettingsStore`; replace the body of `di/StoreBindings.kt`.
4. Learning helpers (pure, in `engine/codec/` or a new `engine/learn/` you own): actual vs planned minutes per title/category from `ExecutionLog`, for the assistant's context.

Acceptance: codec round-trip tests green; app compiles in CI; stores satisfy the same behaviour as the in-memory fakes (write a shared contract test in pure code that runs against the fakes, and document how to run it against Room on a device).

## Stream G: sleep (`redesign/g-sleep`)

`docs/REDESIGN.md` sections 1.3 and 3.2 and Stream A there (4.9). Side stream: sleep feeds coaching and the AI context; it never feeds the scheduler.

1. Episodes: keep every record of any length, merge records separated by a short gap, keep awake stretches and gaps as interruptions; wake-anchored sleep day; total, core, extra.
2. Source rule: Fitbit (`dataOrigin` package `com.google.android.apps.fitness` or Fitbit's) trusted; another writer only for a day Fitbit has none; never sum overlaps.
3. Readiness from rolling 24h/72h sleep against a configurable need; "final" when the next episode is unlikely, not at a clock time.
4. Fixtures: single block, Fajr split, second sleep after 11:00, three fragments, two writers overlapping; assert totals. Keep existing readiness/sleep tests green or port them.
5. App side in `app/.../sleep/*` only as needed to feed episodes from Health Connect. Expose a small pure summary function the assistant can call later (document it in `notes/G.md`).

Acceptance: `jvm-test.sh core` green with the new fixtures; app compiles in CI.

## Merge order (master)

F, A, G first (leaves), then B (uses A and F), then E and C, then D. After each merge: `jvm-test.sh`, and CI on the integration branch. Then delete dead legacy code, write `SETUP.md` and `MANUAL.md`, and push to `main`.
