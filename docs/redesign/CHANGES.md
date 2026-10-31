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
