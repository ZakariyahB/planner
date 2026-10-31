# Contract change requests

The contracts are the files marked "Contracts (Stream 0)" in `android/engine`, `android/calendar` and `android/app/.../di`.
A stream that needs a different type or signature does not edit them: it appends an entry here on its branch, and the
master agent applies accepted changes when merging.

Format:

```
## YYYY-MM-DD · Stream X · short title
- What:
- Why:
- Affects: streams
- Proposed change: (code or signature)
- Status: proposed | accepted | rejected (by master)
```

---

## 2026-10-31 · Stream F · Instrumented-test setup so the store contract runs against Room
- What: in `android/app/build.gradle.kts`, add `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` to `defaultConfig`, and `androidTestImplementation` of `androidx.test:runner:1.6.2` and `androidx.test.ext:junit:1.2.1`. Then add `android/app/src/androidTest/kotlin/app/planner/store/RoomStoreContractTest.kt` (the text is in `docs/redesign/notes/F.md`).
- Why: `StoreContract` (`engine/codec`) is the shared behaviour of the storage ports. It runs against the in-memory fakes on the JVM. Running it against `PlannerStoreDatabase` needs an Android runtime (Room's SQLite), and the app has no instrumented-test dependencies. Gradle files and `src/androidTest` are outside Stream F's paths.
- Affects: master only. CI does not run instrumented tests; this is for a device or emulator (`./gradlew :app:connectedDebugAndroidTest`).
- Proposed change: as above.

## 2026-10-31 · Stream G · sleep need in Settings
- What: a configurable sleep need for readiness.
- Why: readiness now scores rolling 24h and 72h sleep "against a need you set" (REDESIGN 3.2); today the need is the 7h default in `ReadinessConfig` because no settings type carries it.
- Affects: G (reads it), D (Settings screen field), F (codec default).
- Proposed change: `Settings.sleepNeedMin: Int = 420` in `engine/model/Model.kt`. When merged, `app/.../sleep/ReadinessRepository` builds `ReadinessConfig(needMin = settings.sleepNeedMin)` instead of the default.
- Status: proposed

## 2026-10-31 · Stream G · keep the sleep record's writer in storage
- What: a nullable `origin` (Health Connect `dataOrigin` package) column on the legacy `sleep_sessions` table (`app/.../data/SleepEntity.kt`, master-owned), mapped to `SleepSession.origin`.
- Why: the Fitbit-first source rule needs the writer. Without the column, Stream G applies the rule at sync time (only the chosen records are stored), which works but cannot be re-run on stored rows or shown in the UI.
- Affects: master (Room migration v6 to v7), G.
- Proposed change: `val origin: String? = null` on `SleepEntity`, written by `toEntity` and read by `toSession`; migration `ALTER TABLE sleep_sessions ADD COLUMN origin TEXT`.

## 2026-10-31 · Stream E · prompt times and night-before switch in NotificationPrefs
- What: add the weekly prompt times and an on/off switch for the night-before review to `NotificationPrefs`.
- Why: newplan 10 says all ping rules are settings; today the Sunday deadlines time (19:00), the Fajr prompt day/time
  (Sunday 12:00) and whether the night-before review is sent are hard defaults in `engine/nudge/NudgeSchedule`. Stream D's
  Settings screen cannot offer them without contract fields.
- Affects: E (reads them), D (Settings), F (codec defaults; tolerant decoding covers old rows).
- Proposed change:
  ```kotlin
  data class NotificationPrefs(
      // ... existing fields ...
      val nightBeforeReviewEnabled: Boolean = true,
      val sundayDeadlinesAt: LocalTime = LocalTime.of(19, 0),
      val fajrPromptDay: DayOfWeek = DayOfWeek.SUNDAY,
      val fajrPromptAt: LocalTime = LocalTime.of(12, 0),
  )
  ```
  Stream E then builds `NudgeSchedule` from these (one line in `CheckInScheduler`).
- Status: proposed
