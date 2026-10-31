# Stream G: sleep

Branch `redesign/g-sleep`. Pure logic in `android/core/scheduler/.../readiness/*`; app side in `android/app/.../sleep/*`.
Sleep feeds the readiness card, coaching and the assistant's context. It never feeds the v2 scheduler.

## What changed for the user

- **Every sleep counts.** A second sleep after 11:00, an afternoon nap and a 15-minute fragment are all in the day's
  total. The old rules dropped records under 20 minutes and anything starting between 11:00 and 19:00.
- **Sleep day, not night.** A day runs from one main sleep to the next and is dated by the morning he woke. It shows
  the total, the core (longest episode) and the extra (everything else: the block after the Fajr wake-up, a late
  second sleep, naps).
- **One source.** Records written by Fitbit (`com.fitbit.FitbitMobile` or `com.google.android.apps.fitness`) are
  trusted. Another app's records (e.g. Samsung Health) are used only for a day Fitbit has nothing for, and then only
  one other app. Two apps recording the same night are never added together. Records typed in by hand always count.
- **Readiness** scores sleep in the 24 and 72 hours up to the main wake-up against the need (7h for now), plus
  7-day debt, lights-out timing and yesterday's completion. Weights: 24h 25, 72h 15, debt 25, lights-out 20, done 15.
  The 24h window never reaches back past yesterday's main wake-up, so waking earlier than yesterday does not pull in
  the end of yesterday's sleep.
- **Final when another sleep is unlikely**, not at 07:00/10:00: he has been awake 4 hours; or 30 minutes have
  passed (or the phone was unlocked) and the last sleep ended inside his usual wake window, or the window is over,
  or the unlock was inside the window. The window is learned from the last 14 days' main wake-ups (25th percentile
  minus 30 min to 75th percentile plus 60 min, once 5 days are known; 07:00 to 10:00 before that). A sleep that
  ended at Fajr is still not enough on its own. "Waiting for data" stays.

## How it works (`readiness/Episodes.kt`)

1. **Episodes** (`SleepEpisodes.build`): records sorted by start; a record starting less than 30 min after the
   current episode's end joins it. The gap is kept as an awake period; only the part of an overlapping record past
   the episode's end adds sleep (prorated), so overlaps are never counted twice.
2. **Periods**: episodes less than 3h apart are one sleep period (a Fajr split is one period).
3. **Main sleeps**: biggest period first; a period is a main sleep unless a bigger one is within 14h (midpoint to
   midpoint).
4. **Days**: a period between two main sleeps belongs to the earlier day if it starts within 16h of that day's main
   wake-up, else to the next. Wake date = local date of the main sleep's end; two days on the same date are merged.
5. **Source rule** (`SleepSources.select`): days are first built from every record; per day, Fitbit's records if
   it has any, else the single other writer with the most recorded time; manual records always. Days are then
   rebuilt from what was kept.

All the numbers are in `SleepRules` and `ReadinessConfig`.

`SleepNight` (the one-night view the legacy screens, insights, routines and AI tools use) is now built from a
`SleepDay`: `blocks` = the main sleep, `extra` = the rest. `asleepMin` is the whole day; `nightAsleepMin` the main
sleep only; `lightsOut`, `lastEnd`, `inBedMin`, `interruptions` and `phases` describe the main sleep.

## For the assistant (Stream C)

Pure, in `:core:scheduler`:

```kotlin
SleepSummaries.summarise(days: List<SleepDay>, now: Instant, zone: ZoneId, needMin: Int = 420): SleepSummary
SleepSummaries.text(summary: SleepSummary): String
```

`SleepSummary` has `asleep24hMin`, `asleep72hMin` (ending at `now`), `debt7Min`, `average7Min`, `needMin` and one
`SleepDayLine` per recent day (total, core, extra, episodes, interruptions, awake minutes, lights-out, wake).
`text` gives a few lines such as
`Last 24h: 6h 52m asleep (need 7h). Last 72h: 13h 52m. 7-day debt: ...` then
`2026-10-28: 6h 52m (core 4h 42m, extra 2h 10m), 00:32 to 09:14, 1 interruption, 1h 50m awake`.
In the app: `ReadinessRepository.sleepSummary()` (and `recentDays(count)` for the raw days).

## App side

- `SleepRepository.syncFromHealthConnect` reads each record's `metadata.dataOrigin.packageName` and stores only the
  records the source rule keeps (the legacy `sleep_sessions` table has no origin column; see `CHANGES.md`).
- `ReadinessRepository` builds sleep days from the stored rows and uses the new engine. `ReadinessView` gained
  `day: SleepDay?`. A stored final score is still kept unless the sleep on record changed by 10 min or more; a
  correction is now rescored directly as final.
- Every existing caller keeps its signatures (`evaluate`, `recentNights`, `finalFor`, `finalBandToday`,
  `ReadinessView.night/recentNights/forecast`, `ReadinessInputs` four-argument constructor, `ReadinessState`).

## Differences from the plan and why

- The fixtures are built from the shapes of his real Takeout nights (the times already in the old tests), not from
  a fresh export; the "last 14 real nights within 10 minutes of Fitbit" check needs a device (below).
- The main wake-up is the end of the main sleep *period* (the second block after Fajr), not the end of the longest
  episode: with a Fajr split the longest episode is usually the first block, which would make the Fajr wake-up the
  "main" one.
- The source rule runs at sync time because the writer cannot be stored yet (CHANGES.md request).
- Not done in this stream (STREAMS.md G lists 1 to 5 only): hand edits (merge, split, delete, add) that survive a
  re-sync, interruption reason tagging UI (the `InterruptionReason` field exists on `AwakePeriod`, defaulting to
  null), the Reflect sleep timeline composables, and a `sleep_episodes` table.
- The need is the 7h default until `Settings.sleepNeedMin` exists (CHANGES.md request).

## Test by hand

1. Open the app with Fitbit syncing into Health Connect; tap Refresh on the readiness card. Compare the last 14 days'
   totals with the Fitbit app: each should be within 10 minutes (Fitbit shows naps separately; add them up).
2. A morning with a Fajr wake-up: after the first block the card says "Readiness so far" with back-to-bed advice;
   after the second block and 30 minutes it is final, and the total includes both blocks.
3. Take a nap after lunch: the day's total grows; the morning's final score does not change.
4. If Samsung Health (or another app) also writes sleep: the total matches Fitbit's, not the sum.
5. A night typed in by hand still appears and counts.
