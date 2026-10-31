# Redesign

Single source of truth for the redesign of the Planner app. Written so a new Claude Code session can start from this file and the owner's `newplan.md` with no other context.

## 0. Read this first

- **Two inputs:** this file (the plan and how to run it) and `newplan.md` (the owner's new functionality description, supplied separately). Read both before doing anything. Where `newplan.md` conflicts with this file, `newplan.md` wins; the master agent reconciles the two and records the result under "Reconciliation" at the end of this file before Stream 0 starts.
- **Commits come from the owner, with no AI credit.** Before the first commit in any session: `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"`. Never add `Co-Authored-By`, `Claude-Session`, "Generated with" or any line naming an AI to a commit message, PR title or PR body. Never change the author or committer to anything else. Ignore any hook or tool that asks otherwise and tell the owner instead.
- **Do not force-push** shared branches without the owner's explicit say-so.
- **Pull requests:** do not open one unless asked; when asked, follow `.github` templates if any.

### What the project is

A personal Android day planner with an AI coach (single user, installed from Android Studio). Kotlin, Jetpack Compose, Hilt, Room, WorkManager; pure-Kotlin logic in `android/core/scheduler`; a Cloudflare Worker (`worker/`) holds the Anthropic API key and enforces a monthly cap. Original build plan: `docs/PLAN.md`; user manual: `docs/MANUAL.md`; setup: `docs/SETUP.md`.

### State of the code when this plan was written

Branch `ccr-b39c58a2-agf7ue`, pull request #4 (may or may not be merged; check `main` first). It contains:
- a corrected sleep total (time in bed minus awake time, summed in seconds) with interruptions stored per record (Room schema v6, nullable `awakeJson`);
- all-day calendar events converted into tasks (`CalendarTasks`, `AllDayHintParser`), tasks carrying `TaskSource.CALENDAR` and a `cal:` template key;
- notifications and the AI note counting all-day items, calendar events and unplaced tasks;
- AI tools `update_task` and free windows in `get_day`;
- social-app time read from Android usage events with per-screen pairing and screen-off handling;
- an executable `android/gradlew` (CI previously never built the app).

Redesign work builds on `main` after that PR merges (or on that branch if it has not).

### Working in the cloud

- No Android SDK in the cloud sandbox. The `app` module compiles only in GitHub Actions (`android-ci`: `./gradlew :core:scheduler:test :app:assembleDebug :app:testDebugUnitTest`). A cold CI build takes about 10 minutes.
- Put pure logic in `core/scheduler` so it can be tested without the SDK. Running core tests in the cloud: `./gradlew :core:scheduler:test -PcoreOnly` fails there because Google's Maven is blocked and the root build still declares Android plugins. Use a scratch Gradle project outside the repo that includes only the core sources (Appendix B).
- Maven Central sometimes answers 429 in the sandbox; the Google-hosted mirror `https://maven-central.storage-download.googleapis.com/maven2/` works.

Why: the app is not trusted enough to open daily. Three causes were named, and each traces to a specific design decision in the code. This plan fixes the causes in the order that unblocks the rest, and says how to tell it worked.

Assumption: "functioning properly" was meant as "not functioning properly".

Direction (from the owner): the app is a **companion that plans the day for you**, in the spirit of Jarvis. You check in on it; it does the work. The owner's verdict on the current app is that it has been "quite useless", so the redesign is judged by one thing: does it do something for you without you doing the planning.

## 1. What is wrong, and where it comes from

### 1.1 Calendar items and tasks are different things

The app has three separate models: tasks (movable, tickable), fixed events (immovable, not tickable, never in the task list) and, since the last release, all-day events converted into tasks by a bolt-on sync.

- A lecture at 14:00 is a `FixedEvent`: it shows on the timeline but not in Today's list, cannot be ticked, has no progress or notes.
- An all-day "PintOS (3h)" is a task with a templateKey `cal:...`, and its length and time are parsed out of its title.
- A task you type in is a third path with its own editor.

You have to know which kind a thing is before you know what you can do with it. That is the difference you are feeling, and a nicer screen will not remove it: it comes from the data model.

### 1.2 The AI cannot make an impact

- It can only move blocks that already exist (`move_block`, `remove_block`, `drop_task`) or set one task's time (`update_task`). It cannot build a day or a week, split work, or place something that has no block.
- Anything beyond "same day, within 60 minutes" needs a tap on an approval card, so most useful changes stop and wait.
- It is reactive: it runs when you open chat. The only unattended run is a 45-word, read-only note at 23:30.
- It has no memory of you (how long things really take, when you focus, what you skip) and no goals beyond the task list, so its advice is generic.
- It stops after 8 tool rounds, which is too few to plan a week.
- Nothing measures whether its changes helped, so there is no way to improve it.

### 1.3 Sleep ignores broken-up sleep

`SleepGrouping` (core/scheduler `readiness/Sleep.kt`) was written for one night of one or two blocks:

- A record starting 11:00–19:00 is a nap and is ignored, so a second sleep that starts late morning vanishes from the total.
- Records under 20 minutes are dropped, so a fragmented night loses its fragments.
- A night belongs to the date of the record's start, with a fixed 30-minute merge rule, so the Fajr wake and second sleep are patched in rather than modelled.
- Fitbit and Samsung Health can both write to Health Connect. Only overlap is handled; the preferred source is never chosen.
- The need is a flat 7 hours and readiness is "final" by clock rules (07:00 / 10:00), not by the sleep itself.

### 1.4 Underneath all three: too much to maintain

Four tabs plus chat plus settings (about 5,600 lines of UI), six notification routines, and a Sunday review. Each part asks something of you daily. If the first 10 seconds of the app do not tell you what to do now, it will not be opened.

## 2. Principles

1. **One thing, many constraints.** Every entry is an item. Differences between a lecture, a coursework block and an all-day reminder are values of fields, never separate code paths or screens.
2. **The app decides, you veto.** Changes are applied and shown as a diff you can undo (decided: apply with Undo by default, ask only before dropping something). It never asks a question it could answer itself.
3. **The first screen is a briefing, not a dashboard.** It says what the companion has done and what is next, in a few lines, and you can talk or type back.
4. **Data model before UI, UI before AI polish.** The AI and the screens both depend on the model.
5. **Measure it.** Each stream has an acceptance list, and the app logs a usage signal.

## 3. Target design

### 3.1 The unified item

```
Item(
  id, title, kind-less,
  duration,                       // minutes, or null = unknown (AI/estimator fills it in)
  time: Fixed(start,end)          // hard: a meeting, an exam, a lecture you attend
      | Window(day, earliest?, latest?)  // must happen that day, optionally inside hours
      | Due(deadline)            // anywhere before this
      | Open                      // whenever
  movable: Boolean,               // fixed items are unmovable by default, anything else movable
  source: Manual | Calendar(calendarId, eventId) | Template | AI | Commitment,
  status: Open | Partial(minutesLeft) | Done | Skipped | Dropped,
  priority, module/tag, notes, links
)
```

- A timed calendar event becomes `Fixed`, `movable = false`. An all-day event becomes `Window(day)` with a default duration. A typed task is `Due` or `Open`.
- Everything is ticked, edited, progress-logged and dropped the same way. A fixed lecture can be marked "attended" or "skipped (watching the recording)", which is data the planner and the AI can use.
- **Calendar rules** replace the current selection list: per calendar, choose *Busy only* (block time, no item), *Item* (becomes an item you can tick), or *Ignore*. Lectures: the Lectures calendar is *Item* with a recording rule. Every lecture slot is online and watched, so it is never a fixed block: it becomes a "watch" item, available from the end of the slot plus the recording delay (about an hour), with a default duration of the slot length, due by the end of the week, and placed by the planner like any other work. Tutorials or anything you do attend live stay *Busy only* or become fixed items.
- Title hints (`(3h)`, `(1h, from 14:00)`) stay, but as one of several ways to fill duration and time, alongside edit-in-place and the AI's estimate.
- One list on Today and one timeline on Plan, drawn from the same query. Origin is a small badge.
- Migration: tasks, commitments and calendar-derived tasks map 1:1 into items. Fixed events are materialised as items on import, keyed by calendar event id so re-syncs update instead of duplicating. Room auto-migration plus a one-off data migration, with the backup file as a rollback.

### 3.2 Sleep as episodes, not nights

- **Episode:** any run of sleep (a record, or records merged when the gap is short). Keep every episode of any length. Short ones are stored but excluded from the "main sleep" label only.
- **Sleep day:** a 24-hour window anchored at the main wake-up, so a night and its second sleep and any nap belong together. Total = all sleep in the window; the app also shows *core* (longest episode) and *extra* (rest).
- **Interruptions:** awake stretches inside an episode and gaps between episodes, with a reason tag you can set (Fajr, toilet, couldn't sleep).
- **Sources:** your sleep comes from Google Fitbit through Health Connect, so Fitbit is the single trusted source (decided). Read `dataOrigin`; use only Fitbit records, and fall back to any other writer (such as Samsung Health) only for a night Fitbit has none. Never sum overlapping sources.
- **Readiness** uses rolling sleep over the last 24 and 72 hours against a need you set, plus timing, not "last night". It is final when the next episode is unlikely (awake for N hours or past a learned wake window), not at a fixed clock time.
- **Edit tools:** merge, split, delete or add an episode by hand, because wearable data is wrong sometimes. Edits are kept when Health Connect re-syncs.
- **Test data:** build fixtures from real exported nights (single block, Fajr split, late second sleep after 11:00, three fragments, two sources) and assert the totals. This is the acceptance test for Stream A.

### 3.3 The AI becomes the planner

The AI is the product, and the rest of the app is its data and its display. It changes from answering questions to running your day, the way Jarvis does: it prepares, acts, reports and waits to be told otherwise.

- **Daily rhythm it owns:** a morning briefing (sleep, readiness, today's plan already built, what it moved and why), live repair through the day, an evening review and tomorrow's plan, applied unless you object.
- **Talk to it:** voice first ("Halaqa ran over", "I'm wiped, lighten today"), typed second. One command bar on every screen; replies are short and say what changed.
- **It acts, then tells you:** apply with Undo is the default for every change except dropping something (decided).

- **Modes:**
  1. **Plan**: build or rebuild a day or week from the items, free time, sleep and readiness; return one proposal that is a diff (adds, moves, splits, estimates, drops).
  2. **Repair**: triggered by an event (overrun, missed block, new item, poor sleep), returns a smaller diff.
  3. **Coach**: sleep and workload advice from real numbers, unchanged.
- **Richer tools:** `place_item`, `split_item`, `set_estimate`, `schedule_week(range)`, `defer_item`, `set_preference`. The scheduler stays the single validator: the model proposes, the scheduler checks hard rules and returns violations it can fix.
- **Trust ladder instead of the 60-minute rule:** per change type (same-day move, move to another day, split, estimate change, drop) choose *Ask / Apply with Undo / Never*. Decided defaults: apply with Undo for everything except drop, which asks. Each action is one transaction with one Undo.
- **Proactive:** event triggers (overrun, 15 minutes after an item should have started with no activity, new all-day item, bad night) run the Repair mode and notify with action buttons ("Moved PintOS to 16:15. Undo"). The 23:30 run becomes a Plan run for tomorrow, not a note.
- **Memory:** a small structured profile the app keeps (not free text): actual vs estimated minutes per module, hours you actually focus, items you routinely skip, preferences you set. It is sent as a compact context block every run, cached.
- **Learning loop:** log plan vs actual per item. Estimates are corrected by the app's own history before the AI sees them.
- **Rounds and cost:** raise the round limit for Plan runs, give Plan runs a bigger budget and Repair runs a small one; keep the monthly cap. Use a cheaper model for Repair, the stronger one for Plan.
- **Evaluation harness (core):** a set of replayable scenarios (week with three deadlines and a bad night; Halaqa overruns; an empty day with five all-day items) with a stub model plus recorded real runs, scoring: valid plan, deadlines met, load within cap, number of taps needed. Run in CI. This is how "impact" gets measured and prompts get improved without guessing.

### 3.4 Three surfaces

1. **Companion (home):** the briefing and the command bar. Today's changes it made with Undo, the current or next item with start and duration, remaining items below, readiness as one line. No setup cards once set up.
2. **Day / Week:** the single timeline, with items, sleep episodes shaded, free time visible. Drag to move. Tap an empty slot to add or ask the AI to fill it.
3. **Reflect:** sleep timeline (episodes, interruptions), planned vs done, social time. Read-only.

The AI is not a separate sheet: the command bar sits at the bottom of every surface ("Plan my week", "Halaqa ran over"), with voice, and suggestion chips appear inline. Settings shrinks to: Calendars (with the rules above), Sleep (source, need), AI (autonomy per change type, cost), Notifications (fewer: morning plan, repair actions, evening wind-down), Backup.

Also: a home-screen widget and a lock-screen notification showing the current item. You check in, you do not manage: the target is a glance and at most one reply a day.

## 4. Implementation streams

The work is split into seven independent implementations that run in parallel, one agent each, and a master agent merges them. They share only the interfaces in section 4.8 and each builds against fakes of the others.

### 4.1 How it fits together

```
main
 └─ redesign/contracts        ← master agent, first, about a day: shared types, DB schema, interfaces, fakes
     ├─ redesign/a-sleep         Stream A  sleep engine
     ├─ redesign/b-items         Stream B  unified items and scheduler
     ├─ redesign/c-companion     Stream C  AI brain
     ├─ redesign/d-surfaces      Stream D  screens, command bar, voice, widget
     ├─ redesign/e-runtime       Stream E  proactive runtime, notifications, event log
     ├─ redesign/f-calendar      Stream F  calendar sources, lecture rules, write-back
     └─ redesign/g-data          Stream G  migration, backup, CI, docs
          ↓ each opens a PR into
     redesign/integration       ← master agent merges in the order in 4.6, then one PR to main
```

Streams only talk to each other through the interfaces in section 4.8. Each develops against fakes of the others, so none waits for another to finish.

### 4.2 Stream 0: contracts (master agent, before anything else)

Done on `redesign/contracts` and merged into `redesign/integration` before the other branches are cut.

1. Add the shared types and interfaces from section 4.8 to `android/core/scheduler` (pure Kotlin, compiles with no Android SDK), with in-memory fakes under `src/testFixtures` or `src/test`.
2. Make **all** database changes in one migration (version 6 to 7): new tables `items`, `sleep_episodes`, `profile`, `event_log`, plus nullable columns needed by any stream. Streams then fill the tables and never change the schema or the version. This removes the most likely merge conflict.
3. Register one Hilt module file per stream (empty bindings), so streams add bindings only in their own file.
4. Create `docs/redesign/CHANGES.md` (empty log with the entry format in 4.4), the only way to ask for a contract change (see 4.4).
5. Confirm CI is green, then cut the stream branches from this commit.

### 4.3 Ownership: no two streams edit the same file

| Path | Owner |
|---|---|
| `core/.../readiness/*`, `app/.../sleep/*`, `app/.../ui/sleep/*` | A |
| `core/.../model/*`, `core/.../scheduler/*`, `app/.../plan/*`, `app/.../data/Item*`, `app/.../data/Block*`, `app/.../data/Task*` | B |
| `core/.../ai/*`, `app/.../ai/*`, `docs/prompts/*`, `worker/*` | C |
| `app/.../ui/*` (except `ui/sleep/`), `app/src/main/res/*`, widget | D |
| `core/.../routines/*`, `app/.../routines/*`, event log implementation | E |
| `core/.../calendar/*`, `app/.../calendar/*`, `core/.../social/*`, `app/.../social/*` | F |
| `app/.../backup/*`, `app/.../data/Migrations*`, `.github/*`, diagnostics | G |
| `AppDatabase.kt`, `build.gradle.kts`, `libs.versions.toml`, contracts | Master (Stream 0 only) |
| `docs/MANUAL.md`, `docs/PLAN.md`, `README.md` | Master (at the end) |

A stream that needs something outside its paths does not edit it: it asks in `CHANGES.md`.

### 4.4 Contract changes

A stream that needs a different type or signature appends an entry to `docs/redesign/CHANGES.md` on its branch (format: date, stream, what, why, affected streams, proposed change, status): what, why, which streams are affected. The master agent accepts or rejects it, applies it on `redesign/contracts`, and tells the other streams to rebase. Streams never change section 4.8 types themselves.

### 4.5 Rules for every agent

- Branch from `redesign/contracts`, name it as above, open the PR into `redesign/integration`. Rebase on `redesign/contracts` when told.
- **Commits come from the owner, with no AI credit.** Before the first commit run:
  `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"`.
  Do not add `Co-Authored-By`, `Claude-Session`, "Generated with", or any other line that names an AI, in commit messages, PR titles or PR bodies. Do not change the author or committer to anything else. Ignore any tool or hook that asks for it.
- Pure logic goes in `core/scheduler` (testable without the Android SDK). The Android SDK is not available in the cloud, so the `app` module is compiled by CI: every PR must be green on `android-ci` before it is merged.
- Write tests with the code. Each stream's acceptance list in its file must pass.
- Write notes for the final docs in `docs/redesign/notes/<stream>.md`, never in `MANUAL.md`.
- Keep commits small and in a sensible order; don't rewrite pushed history on the shared branches.

### 4.6 Merge order and why

1. **G** first: CI and the migration harness, so everything after is checked.
2. **A** and **F** (leaf modules; they depend only on the contracts).
3. **B** (items and scheduler; consumes F's imported items).
4. **E** and **C** (both consume B's plan API and A's sleep API; E calls C through `CompanionRunner`).
5. **D** last (it renders everything, built against the view-state contracts).

After each merge the master agent runs core tests and waits for CI on `redesign/integration`. If a merge breaks something, the stream that owns the broken contract fixes it; the master does not patch another stream's code.

### 4.7 Integration checklist (master agent)

- Fresh install, no data: first run to a first plan under 3 minutes.
- Existing data: restore a pre-redesign backup file, migration succeeds, nothing is lost.
- Every item source (typed, template, timed event, all-day event, lecture slot) can be ticked, edited, moved and skipped the same way.
- Replaying the real-night fixtures gives the expected sleep totals.
- The evaluation harness (Stream C) passes in CI.
- Morning briefing, repair notification with Undo, and evening plan each fire once on the right trigger.
- Then: update `MANUAL.md` and `PLAN.md` from the stream notes, open one PR from `redesign/integration` to `main`.
### 4.8 Contracts

Shared types and interfaces. The master agent creates these in `android/core/scheduler` during Stream 0 and finalises the exact signatures; what follows fixes the shape. Streams code against these and against the fakes provided with them. Types live in core so they can be tested without the Android SDK.

##### Item (Streams B, C, D, E, F)

```kotlin
data class Item(
    val id: Long = 0,
    val title: String,
    val durationMin: Int?,              // null = unknown; an estimator or the AI fills it in
    val timing: ItemTiming,
    val movable: Boolean,
    val source: ItemSource,
    val status: ItemStatus,
    val priority: Priority = Priority.NORMAL,
    val tag: String? = null,            // module or course
    val notes: String? = null,
    val availableFrom: Instant? = null, // lecture recordings: slot end plus the delay
    val anchor: Anchor? = null,         // prayer anchor, as today
    val progressMin: Int = 0,           // minutes done on a partly done item
)

sealed interface ItemTiming {
    data class Fixed(val start: Instant, val end: Instant) : ItemTiming   // meeting, exam, a class you attend
    data class Window(val date: LocalDate, val earliest: LocalTime? = null, val latest: LocalTime? = null) : ItemTiming // all-day items, lecture watching
    data class Due(val deadline: Instant) : ItemTiming
    data object Open : ItemTiming
}

sealed interface ItemSource {
    data object Manual : ItemSource
    data object Template : ItemSource
    data class Calendar(val calendarId: String, val eventId: String, val day: LocalDate) : ItemSource
    data object Assistant : ItemSource
    data object Commitment : ItemSource
}

enum class ItemStatus { OPEN, PARTIAL, DONE, SKIPPED, DROPPED }
```

Rules everyone relies on: no code branches on `source` except to show a badge and to key re-imports; "can be ticked / edited / moved / skipped" is true of every item; `movable = false` is the only thing that makes a block unmovable.

##### Plan API (implemented by B, consumed by C, D, E)

```kotlin
interface PlanApi {
    fun observeDay(date: LocalDate): Flow<DayPlan>                    // items placed, fixed events, sleep shading, free windows
    suspend fun items(filter: ItemFilter): List<Item>
    suspend fun freeWindows(date: LocalDate): List<TimeWindow>
    suspend fun validate(changes: ChangeSet): Validation              // hard violations and warnings
    suspend fun apply(changes: ChangeSet, actor: Actor, summary: String): AppliedChange   // one transaction, one undo token
    suspend fun undo(token: UndoToken): Boolean
    suspend fun replan(scope: ReplanScope): PlanSummary
}
```

`ChangeSet` grows the existing ops: `Place(itemId, date, start, end)`, `Move`, `Split(itemId, parts)`, `SetEstimate`, `Defer(itemId, toDate)`, `SetStatus`, `Drop`. B implements them all; C may use any of them.

##### Sleep API (implemented by A, consumed by B, C, D, E)

```kotlin
data class SleepEpisode(val id: Long, val start: Instant, val end: Instant, val asleepMin: Int,
                        val awake: List<AwakePeriod>, val source: SleepSource, val manual: Boolean)

data class SleepDay(val wakeDate: LocalDate, val episodes: List<SleepEpisode>,
                    val totalAsleepMin: Int, val coreMin: Int, val extraMin: Int,
                    val inBedMin: Int, val interruptions: List<AwakePeriod>)

interface SleepApi {
    fun observeDays(count: Int): Flow<List<SleepDay>>
    suspend fun refresh(): SleepSyncResult            // reads Health Connect
    suspend fun addEpisode(start: Instant, end: Instant)
    suspend fun deleteEpisode(id: Long)
    suspend fun mergeEpisodes(ids: List<Long>)
    suspend fun splitEpisode(id: Long, at: Instant)
}

interface ReadinessApi { fun observe(): Flow<ReadinessState>; suspend fun evaluate(): ReadinessView }
```

##### Companion API (implemented by C, consumed by D, E)

```kotlin
enum class CompanionMode { PLAN, REPAIR, COACH, CHAT }

sealed interface Trigger {
    data object MorningBriefing : Trigger
    data object EveningReview : Trigger
    data class Overrun(val itemId: Long, val extraMin: Int) : Trigger
    data class Missed(val itemId: Long) : Trigger
    data class NewItem(val itemId: Long) : Trigger
    data object PoorSleep : Trigger
    data class UserMessage(val text: String, val viaVoice: Boolean) : Trigger
}

data class CompanionResult(val reply: String, val changes: List<AppliedChange>, val pendingApprovals: List<Proposal>, val costGbp: Double)

interface CompanionRunner { suspend fun run(trigger: Trigger): CompanionResult }
interface AutonomyPolicy { fun decision(op: OpKind): Autonomy }     // ASK, APPLY_WITH_UNDO, NEVER; default APPLY_WITH_UNDO except DROP = ASK
```

##### Event log (implemented by E, called by everyone)

```kotlin
interface EventLog { fun log(event: AppEvent) }    // screen opened, change applied/undone, briefing shown, crash; local only
```

##### Screen state (defined here, produced by use cases in C and B, consumed by D)

```kotlin
data class CompanionHomeState(val briefing: Briefing?, val recentChanges: List<AppliedChange>, val now: ItemCard?, val upNext: List<ItemCard>, val readiness: ReadinessLine?)
data class DayState(val date: LocalDate, val items: List<PlacedItem>, val fixed: List<PlacedItem>, val sleep: List<SleepEpisode>, val free: List<TimeWindow>)
```

D builds against fake implementations that return canned `CompanionHomeState` and `DayState`; B and C supply the real ones.

##### Fakes

Each interface above ships with an in-memory fake (`FakePlanApi`, `FakeSleepApi`, `FakeCompanionRunner`, `FakeEventLog`) in core test fixtures, seeded with a realistic week (three deadlines, a Fajr-split night, five all-day items, lecture watch items). Streams must keep their tests running against fakes so they never depend on another stream's branch.

### 4.9 Stream A: sleep engine

Branch `redesign/a-sleep`. Fixes: broken-up sleep. Source of truth for your sleep is Google Fitbit through Health Connect.

#### Goal
Model sleep as episodes inside a wake-anchored sleep day, so a Fajr split, a late second sleep, fragments and naps are all counted, and readiness stops depending on "one night".

#### Owns
`android/core/scheduler/.../readiness/*`, `android/app/.../sleep/*`, `android/app/.../ui/sleep/*`, sleep fixtures and tests.

#### Provides (see 4.8)
`SleepApi`, `ReadinessApi`, `SleepDay`, `SleepEpisode`, and Compose pieces in `ui/sleep/` for Stream D to place: `SleepTimeline`, `SleepDayCard`, `EpisodeEditor`.

#### Work
1. Replace `SleepGrouping` with episode building: keep every record of any length (no 20-minute filter, no 11:00 to 19:00 nap rule); merge records separated by a short gap; keep awake stretches and gaps as interruptions.
2. Sleep day: a 24-hour window anchored at the main wake-up (the longest episode's end); total, core (longest episode) and extra (the rest, naps included).
3. Source rule: read `dataOrigin` from Health Connect and use Fitbit records only; use another writer only for a day Fitbit has none. Never sum overlapping records.
4. Readiness: inputs become rolling 24 h and 72 h sleep against a configurable need, timing, debt and yesterday's completion. "Final" means the next episode is unlikely (awake for a set number of hours or past the usual wake window), not a clock time. Keep the "waiting for data" state.
5. Edit tools: add, delete, merge and split an episode by hand; manual edits survive the next Health Connect sync.
6. Reflect sleep timeline composable: episodes as bars, interruptions marked, the last 7 days.
7. Migrate existing `sleep_sessions` rows into `sleep_episodes` (table created by Stream 0); keep the old table until the release after.

#### Acceptance
- Fixtures from real exported nights (single block, Fajr split, second sleep starting after 11:00, three fragments, Fitbit plus another writer overlapping) all give the expected totals, and the last 14 real nights are within 10 minutes of Fitbit's own totals.
- A nap and a late second sleep are counted; two writers are never double counted.
- A hand edit survives a re-sync.
- Readiness for a Fajr-split day is not computed from the first block alone.

#### Agent prompt
Read sections 1.3 and 3.2 and 4.1 to 4.8 of this file, and `newplan.md`. Implement Stream A as listed above on branch `redesign/a-sleep`. Develop against the contracts and fakes; do not touch files outside your paths. Rules (section 4.5): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.

### 4.10 Stream B: unified items and scheduler

Branch `redesign/b-items`. Fixes: calendar items and tasks being different things.

#### Goal
One `Item` model for everything (typed task, template task, timed event, all-day event, lecture slot, commitment), one scheduler over it, one plan API.

#### Owns
`core/.../model/*`, `core/.../scheduler/*`, `app/.../plan/*`, `app/.../data/Item*`, `Block*`, `Task*` and their DAOs.

#### Provides
`PlanApi` and the `Item` implementation behind it; extended `ChangeSet` ops (`Place`, `Move`, `Split`, `SetEstimate`, `Defer`, `SetStatus`, `Drop`); use cases that produce `DayState` and `CompanionHomeState` pieces.

#### Work
1. Scheduler works on items: Fixed items become unmovable busy time; Window items must land on their day inside their hours; Due and Open items are placed by deadline, priority and `availableFrom`. Keep the existing rules (prayer buffers, work window, Friday, sleep, daily cap, catch-up days).
2. A done, partly done, skipped or dropped item is handled the same whatever its source. Fixed items can be marked attended or skipped.
3. `Split`: one item into several placed parts (the old "never split" rule becomes a default, not a law).
4. Replan transaction with one undo token; `ChangeSet` validation returns hard violations and warnings.
5. Replace the tasks/blocks/commitments split with `items` plus placement rows; remove the all-day conversion bolt-on once Stream F feeds items directly.
6. Free windows and conflicts exposed through `PlanApi`.
7. Migrate existing tasks, commitments and calendar-derived tasks (data side owned with Stream G: B writes the mapping functions in core, G runs them).

#### Acceptance
- No code outside a badge and re-import keying branches on `ItemSource`.
- Every item can be ticked, edited, moved, skipped and dropped identically.
- Existing scheduler tests pass after being ported to items, plus new tests for Fixed, Window, Split, Skipped.
- Repeated imports never create duplicates (idempotent by `ItemSource.Calendar` key).
- Undo of any applied change set restores the prior plan exactly.

#### Agent prompt
Read sections 1.1 and 3.1 and 4.1 to 4.8 of this file, and `newplan.md`. Implement Stream B as listed above on branch `redesign/b-items`. Port the existing scheduler tests before changing behaviour. Develop against fakes for sleep and the companion. Rules (section 4.5): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.

### 4.11 Stream C: AI companion brain

Branch `redesign/c-companion`. Fixes: the AI making little impact. The AI becomes the product: it plans, acts, reports.

#### Goal
A planner agent that builds the day and week, repairs it when things change, applies changes with Undo, and learns how long things take you.

#### Owns
`core/.../ai/*`, `app/.../ai/*`, `docs/prompts/*`, `worker/*`, the evaluation harness.

#### Provides
`CompanionRunner`, `AutonomyPolicy`, `CompanionResult`, the use case producing the briefing, and the evaluation harness.

#### Work
1. Modes: PLAN (build or rebuild a day or week as one diff), REPAIR (small diff after a trigger), COACH (sleep and workload), CHAT. Each has its own prompt, round limit and budget.
2. Tools over `PlanApi`: place, move, split, set estimate, defer, drop, set preference, plus the read tools (day, items, sleep, readiness, social). The scheduler validates; the model fixes violations it is told about.
3. Trust ladder: `AutonomyPolicy` per change type. Defaults: apply with Undo for everything except drop, which asks. One transaction and one undo token per action.
4. Briefing: morning and evening content (what changed, why, what is next) in a few lines, built from real numbers.
5. Profile and learning: record plan versus actual per item and tag; keep a compact structured profile (actual versus estimated minutes, focus hours, routinely skipped items); send it as a cached context block. Correct estimates from history before the model sees them.
6. Worker: model routing (stronger for PLAN, cheaper for REPAIR and scheduled runs), prompt caching, the monthly cap.
7. **Evaluation harness** in core: replayable scenarios (a week with three deadlines and a bad night; Halaqa overruns; an empty day with five all-day items; five lecture watch items), a recorded-run mode and a stub-model mode, scoring valid plan, deadlines met, load within the cap, number of taps needed. Runs in CI.

#### Acceptance
- Harness passes in stub mode in CI; recorded runs are stored for regression.
- PLAN produces a valid week in one run; REPAIR after an overrun moves the minimum needed.
- Applying and undoing an AI change leaves no residue.
- Prompts and tool schemas stay consistent (extend `PromptFilesTest`).
- Cost per day on typical use stays inside the monthly cap.

#### Agent prompt
Read sections 1.2 and 3.3 and 4.1 to 4.8 of this file, and `newplan.md`. Implement Stream C on branch `redesign/c-companion`, against `FakePlanApi` and `FakeSleepApi`. Build the evaluation harness first and make each prompt change measurable. Rules (section 4.5): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.

### 4.12 Stream D: screens, command bar, voice, widget

Branch `redesign/d-surfaces`. Fixes: too much to operate; makes the app feel like a companion.

#### Goal
Three surfaces, an always-present command bar, voice, and a widget. You check in; you do not manage.

#### Owns
`app/.../ui/*` (except `ui/sleep/`, owned by Stream A), resources, navigation, the widget.

#### Provides
The finished UI, built on the state types in section 4.8.

#### Work
1. **Companion (home):** the briefing, changes the companion made with Undo, the current item with start and duration, the rest of today, one-line readiness. No setup cards once set up.
2. **Day / Week:** one timeline of items, fixed events, sleep episodes (shaded) and free time. Drag to move, tap an empty slot to add or ask.
3. **Reflect:** sleep timeline (Stream A's composables), planned versus done, social time. Read-only.
4. **Command bar** on every surface: type or speak; short replies stating what changed; suggestion chips.
5. **Voice:** speech recognition first, typing second.
6. Settings cut to: Calendars (with the rules from Stream F), Sleep, AI autonomy (per change type), Notifications, Backup.
7. Widget and lock-screen "current item".
8. First-run onboarding under three minutes.
9. One list component for items: origin is a badge only.

#### Acceptance
- App opens on the briefing; every item type renders and acts the same.
- Builds and runs against `Fake*` implementations with no other stream merged.
- No screen has an `if (source == …)` outside the badge.
- First run to a first plan in under three minutes on a fresh install.
- Screens pass a basic accessibility check (touch targets, contrast, content descriptions).

#### Agent prompt
Read sections 1.4 and 3.4 and 4.1 to 4.8 of this file, and `newplan.md`. Implement Stream D on branch `redesign/d-surfaces` against the `Fake*` implementations; wire real implementations only by their interfaces. Rules (section 4.5): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.

### 4.13 Stream E: proactive runtime, notifications, event log

Branch `redesign/e-runtime`. Fixes: a companion that waits to be opened.

#### Goal
The companion acts on its own at the right moments and tells you with actions you can tap.

#### Owns
`core/.../routines/*`, `app/.../routines/*`, the event log implementation, notification channels.

#### Provides
`EventLog`, trigger detection, the scheduler of runs, action notifications.

#### Work
1. Trigger detection from app state: morning briefing time (after you wake, from the sleep API), evening review, overrun (an item still open past its end), missed (item past its end with no activity), new item, poor sleep.
2. Call `CompanionRunner.run(trigger)` and turn the result into a notification with actions ("Moved PintOS to 16:15. Undo", "Lighten today"). Each notification: one-tap Undo.
3. Cut the six routines to three: morning briefing, repair actions, evening plan and wind-down; migrate settings.
4. Reliable scheduling: WorkManager plus exact alarms where needed; a rescheduler that survives reboot; the 15-minute job only for sleep and calendar refresh.
5. Local `EventLog` (private, on the phone): screens opened, changes applied and undone, briefing shown, crashes; a one-tap "this was wrong" report with the screen state attached.
6. Daily "did you check in" signal for your own review.

#### Acceptance
- Each trigger fires once per occurrence and not twice after a reboot or a replan.
- Notification actions work from the shade and the lock screen.
- With the companion fake, a full day of triggers produces the expected notifications (tested in core).
- The event log records an applied and undone change and survives restart.

#### Agent prompt
Read sections 3.3 and 3.4 and 4.1 to 4.8 of this file, and `newplan.md`. Implement Stream E on branch `redesign/e-runtime`, calling the companion only through `CompanionRunner` (use `FakeCompanionRunner`). Rules (section 4.5): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.

### 4.14 Stream F: calendar sources, lecture rules, write-back

Branch `redesign/f-calendar`. Fixes: calendar events entering the app as a different kind of thing; lectures treated as fixed.

#### Goal
Every calendar entry arrives as an item by per-calendar rules, with the right timing, and the plan is written back to Google.

#### Owns
`core/.../calendar/*`, `app/.../calendar/*`, `core/.../social/*`, `app/.../social/*`.

#### Provides
An importer that outputs `Item` values keyed by `ItemSource.Calendar`, calendar rules, lecture watch items, write-back, and the social usage reader.

#### Work
1. **Rules per calendar:** *Busy only* (blocks time, no item), *Item* (becomes an item), *Ignore*. Defaults replace the current selection list.
2. **Mapping:** timed event to `Fixed`, movable false; all-day event to `Window(day)` with duration and time read from title brackets, an hour by default; the title-hint parser stays (`AllDayHintParser`) as one source of duration and time.
3. **Lectures:** every slot is online and watched, so the Lectures calendar maps to "watch" items: `availableFrom` = slot end plus the recording delay (default one hour, a setting), duration = slot length, due by the end of the week. Never fixed.
4. Idempotent re-import: update by key, never duplicate; items you edited keep your edits; removed events drop open items only.
5. Write-back: placed items go to the Planner calendar, edits made there are read back; two-way, the app wins conflicts.
6. Social usage: keep reading Android's own usage events (as Digital Wellbeing does); keep per-screen pairing and screen-off handling.
7. Calendar refresh in the background so imports exist before the 23:30 and morning runs.

#### Acceptance
- A fixture week (lectures, tutorials, all-day items with bracket hints, a multi-day event, a deleted event) imports to the expected items and survives re-import unchanged.
- Lecture watch items are never placed before the slot end plus delay.
- Editing an imported item keeps the edit after the next import.
- Existing calendar and social tests pass, ported to items where needed.

#### Agent prompt
Read sections 1.1 and 3.1 and 4.1 to 4.8 of this file, and `newplan.md`. Implement Stream F on branch `redesign/f-calendar`. Output `Item` values only; do not edit the scheduler. Rules (section 4.5): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.

### 4.15 Stream G: migration, backup, CI, docs

Branch `redesign/g-data`. Fixes: risk. Nothing is lost, nothing merges unchecked.

#### Goal
Existing data survives the redesign, backups round-trip, and CI checks every stream.

#### Owns
`app/.../backup/*`, `app/.../data/Migrations*`, `.github/*`, diagnostics, `docs/redesign/notes/*` collection.

#### Provides
The data migration runner, backup format v2, CI.

#### Work
1. **CI first** (merge before the others): run core tests with Gradle on every PR into `redesign/*`; keep `android-ci` (assemble and unit tests); add a job that runs the evaluation harness (when Stream C lands) and a job that fails if a stream edits files it does not own (path rules from section 4.3).
2. **Migration runner:** one-off migration from the old tables (tasks, blocks, sleep sessions, commitments, all-day ticks) to the new ones, run once after the schema upgrade, idempotent, with a pre-migration backup written automatically. Uses mapping functions from Streams A and B.
3. **Backup format v2:** items, placements, episodes, profile, event log opt-in; still restores v1 files; unknown tables ignored; refuses newer versions.
4. Restore test: a real pre-redesign backup file restores and migrates with nothing lost.
5. Diagnostics screen data (what the event log shows), crash capture hook for Stream E.
6. Collect the per-stream notes in `docs/redesign/notes/` for the master agent.

#### Acceptance
- A v1 backup file from today's app restores, migrates, and every task, block and sleep row maps to an item or episode.
- Running the migration twice changes nothing.
- CI fails for a PR that edits another stream's files or breaks core tests, and passes for a clean one.
- Backup v2 round-trips.

#### Agent prompt
Read sections 4.1 to 4.8 of this file and `newplan.md`. Implement Stream G on branch `redesign/g-data`; land the CI changes first on their own small PR. Rules (section 4.5): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.

### 4.16 Master agent prompt

Read this whole file and `newplan.md`. Reconcile them (write the outcome under "Reconciliation" at the end of this file and commit it). Then do Stream 0 (4.2) on `redesign/contracts`: shared types and fakes in `core/scheduler`, one database migration for all new tables, empty Hilt modules, `docs/redesign/CHANGES.md`; wait for green CI; cut the seven stream branches. After the streams report, merge in the order in 4.6, run the checklist in 4.7, and open one pull request to `main` when asked. Follow section 0 for commit rules: commits come from Zakariyah Bello, with no AI credit anywhere.

## 5. Risks

- **Migration.** Merging three models into one touches tasks, blocks, the sync with the Planner calendar and backup. Mitigation: migrate behind a backup, keep the old tables for one release, add a restore test.
- **AI autonomy applied wrongly.** Apply-with-Undo can still surprise you. Mitigation: one-tap Undo on every notification, a change history, and the scheduler's hard rules stay non-negotiable.
- **Cost.** Plan runs use more tokens. Mitigation: cached profile and prompts, cheaper model for Repair, the existing monthly cap.
- **Scope.** This is a rewrite of the core. If time is short, merge streams G, A, F and B first: they fix two of the three complaints without touching the AI.
- **Fitbit through Health Connect.** Sync delay and background-read limits on the phone are assumptions I could not test here. Stream A starts by exporting your real Health Connect records, and the "waiting for sleep data" state stays.

## 6. Decisions

Made:

1. **Sleep source:** Google Fitbit, read through Health Connect. Fitbit is the single trusted source.
2. **Lectures:** all slots are online and watched, so they are flexible "watch" items, not fixed blocks.
3. **Autonomy:** apply with Undo by default; ask only before dropping something.
4. **Role:** a companion you check in on, not a tool you operate. You do little; it plans and adjusts.
5. **Why it matters:** the current app has not been useful, so the redesign has to show value in the first week of use, not after a long setup.

Open, to be settled by `newplan.md`:

- The exact morning and evening rhythm (what time the briefing arrives, whether you reply by voice).
- What the companion may do outside the planner, if anything (for example, add events to Google Calendar, message someone).
- Whether coursework should be broken into sessions automatically from deadlines (a goals model), or stay as items you enter.

## 7. Reconciliation

Filled in by the master agent after reading `newplan.md`: list each point where it changes this plan, and the resulting change to the contracts or streams.

(Not yet done.)

## Appendix A: what exists today (for orientation)

- **Planner:** deterministic scheduler in `android/core/scheduler/.../scheduler/Scheduler.kt` (earliest-deadline-first, prayer buffers, work window, Friday cut-off, daily cap 360 min, catch-up days, pinned start times). Blocks, tasks, fixed events and commitments are separate.
- **Readiness:** `readiness/Readiness.kt` and `Sleep.kt` (score from last night's sleep, 7-night debt, lights-out lateness, yesterday's completion; bands green/amber/red set the load).
- **AI:** `ai/Agent.kt` loops on a model through the Worker with tools in `docs/prompts/tools.json` and the system prompt in `docs/prompts/system.md`; changes go through `AiPlanService` / `ProposalBook` (small same-day changes apply; others need approval). A read-only 23:30 note runs on the cheaper model.
- **Routines:** six notifications (provisional readiness, morning brief, work stops, plan ready, lights-out, Sunday review) plus social-time nudges.
- **Syncs:** Google Calendar (reads selected calendars, writes timed blocks to a "Planner" calendar, two-way for time edits), Health Connect (sleep), Android usage stats (social apps), manual JSON backup. Everything else stays on the phone; only summaries go to the model.
- **UI:** four tabs (Today, Plan, Tasks, Reflect), an AI chat sheet, Settings.

## Appendix B: running core tests in the cloud

Create a scratch Gradle project outside the repo (for example under `/tmp/coretest`) with this `settings.gradle.kts`:

```kotlin
rootProject.name = "coretest"
pluginManagement { repositories { maven("https://maven-central.storage-download.googleapis.com/maven2/"); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { maven("https://maven-central.storage-download.googleapis.com/maven2/") } }
```

and this `build.gradle.kts` (adjust the repository path):

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { kotlin("jvm") version "2.0.21"; kotlin("plugin.serialization") version "2.0.21" }
val core = "/path/to/planner/android/core/scheduler/src"
sourceSets { main { kotlin.srcDir("$core/main/kotlin") }; test { kotlin.srcDir("$core/test/kotlin") } }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform(); workingDir = file("/path/to/planner/android/core/scheduler") }
```

Run `gradle test`. Delete the scratch project's `build` and `.gradle` directories if the incremental cache gets corrupted.
