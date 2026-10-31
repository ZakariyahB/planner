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
- Status: proposed
