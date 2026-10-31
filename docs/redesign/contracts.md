# Contracts

Shared types and interfaces. The master agent creates these in `android/core/scheduler` during Stream 0 and finalises the exact signatures; what follows fixes the shape. Streams code against these and against the fakes provided with them. Types live in core so they can be tested without the Android SDK.

## Item (Streams B, C, D, E, F)

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

## Plan API (implemented by B, consumed by C, D, E)

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

## Sleep API (implemented by A, consumed by B, C, D, E)

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

## Companion API (implemented by C, consumed by D, E)

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

## Event log (implemented by E, called by everyone)

```kotlin
interface EventLog { fun log(event: AppEvent) }    // screen opened, change applied/undone, briefing shown, crash; local only
```

## Screen state (defined here, produced by use cases in C and B, consumed by D)

```kotlin
data class CompanionHomeState(val briefing: Briefing?, val recentChanges: List<AppliedChange>, val now: ItemCard?, val upNext: List<ItemCard>, val readiness: ReadinessLine?)
data class DayState(val date: LocalDate, val items: List<PlacedItem>, val fixed: List<PlacedItem>, val sleep: List<SleepEpisode>, val free: List<TimeWindow>)
```

D builds against fake implementations that return canned `CompanionHomeState` and `DayState`; B and C supply the real ones.

## Fakes

Each interface above ships with an in-memory fake (`FakePlanApi`, `FakeSleepApi`, `FakeCompanionRunner`, `FakeEventLog`) in core test fixtures, seeded with a realistic week (three deadlines, a Fajr-split night, five all-day items, lecture watch items). Streams must keep their tests running against fakes so they never depend on another stream's branch.
