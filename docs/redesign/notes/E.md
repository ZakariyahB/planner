# Stream E: check-ins and prompts

newplan sections 10 and 12, 13.4 (notification scheduler tests).

## What is where

| Path | What |
|---|---|
| `android/engine/.../engine/checkin/CheckInPlanner.kt` | Pure: a day's `TodayBlock`s + prayer anchors + `Settings` to the list of pings |
| `engine/checkin/AlarmDiff.kt` | Pure: what a reschedule sets, leaves alone and cancels (stable ids, fingerprints) |
| `engine/checkin/CheckInResponse.kt`, `CheckInLog.kt` | Pure: what each button does (execution log changes, replan reason, snooze, focus question) |
| `engine/checkin/CheckInTexts.kt` | Pure: notification texts, action labels, the "Review change" notice, `OpenTarget` |
| `engine/checkin/AlarmPayload.kt` | Pure: JSON payload carried by each alarm and action; `stillDue` check at fire time |
| `engine/nudge/Nudges.kt` | Pure: Sunday deadlines, weekly Fajr and night-before review prompts; `FajrInput` quick reply parser |
| `android/app/.../checkin/*` | Alarms, receivers, workers, notifications, channels, permissions, `CheckInSetupCard` |
| `app/.../di/CheckInBindings.kt` | Binds `CheckInRescheduler` to `CheckInScheduler` |
| `app/.../routines/*`, `core/.../routines/RoutineSchedule.kt` | Legacy routines trimmed (see "Retired") |

Tests: `android/scripts/jvm-test.sh engine core`. The check-in tests are in `engine/src/test/.../checkin/` and
`.../nudge/`; full reference-week days (Mon, Wed, Fri, Sun) are asserted ping by ping.

## The `open` extra (for Stream D)

Every notification opens `MainActivity` with flags `NEW_TASK | CLEAR_TOP | SINGLE_TOP` and a string extra
`open` (`OpenTarget.EXTRA`). Read it in `onCreate` and `onNewIntent` (and remove it, so a rotation does not reopen it):

| `open` | Sent by | Should open |
|---|---|---|
| `approvals` | "Review change" after Need 30 more / Stop here; the night-before review when proposals are pending | Approval screen. A `changeSetId` extra (`CheckInNotifier.EXTRA_CHANGE_SET`) names the proposal when there is one |
| `today` | Check-in pings, focus question, replan notices that are not proposals | Today |
| `week` | Night-before review | Week (tomorrow) |
| `chat` | Sunday "Any deadlines coming up?" | Chat |
| `settings` | Weekly Fajr prompt | Settings (Fajr / sleep) |

`OpenTarget.parse(intent.getStringExtra(OpenTarget.EXTRA))` turns it back into the enum. The legacy
`RoutineLaunch` extra (`app.planner.routines.TARGET`) is still used by the legacy morning brief / lights-out
notifications and is unaffected.

`CheckInSetupCard(modifier)` (in `app.planner.checkin`) is a ready composable for Today or Settings: it asks for
the notification permission, links to the exact-alarm setting, and shows the one-time battery-optimisation explainer
with a deep link. It renders nothing once all three are fine.

## Behaviour (user-facing)

- **Which blocks ping:** the app's task blocks that are on the calendar (`TodayBlock.ref != null`, not fixed). Classes,
  halaqa and other fixed events never ping. Suggested-but-unapproved blocks have no event yet, so no pings.
- **Start** at the block start: "Lecture - OS, 18:00", body "Until 19:15. Includes 15 min for Isha.", buttons
  **Start / Snooze 10 / Skip**. Start logs `actualStart`; Snooze shows it again 10 minutes later; Skip logs skipped.
- **End** at the block end: "PintOS done?", buttons **Done / Need 30 more / Stop here**. Done logs `actualEnd`, then the
  notification turns into "How was your focus?" with a 1-5 choice (RemoteInput choices; times out after 30 min).
  Need 30 more and Stop here log the outcome and call `ProposalPort.replan(Overrun | StoppedEarly, NOTIFICATION)` in a
  worker; the result is a "Review change" notification. **Nothing is moved until the user approves on the approval
  screen.** Logging is not a calendar edit.
- **Back to back** (next block starts within 5 minutes of the end, or the end ping would be pushed onto the next start by
  a prayer slot): one "Next up: X, 17:31" ping with **Done, start next / Need 30 more / Stop here**.
- **Under 45 minutes of work** (prayer buffer not counted): start ping only, and no prayer break.
- **Prayer break:** a block spanning a prayer gets "Dhuhr break, 15 min" at the prayer time (one per prayer it spans),
  no buttons, gone after the slot.
- **Quiet in prayer slots:** a ping landing in `[anchor, anchor + 15)` moves to the end of the slot (decision: shift, not
  drop, because the end question is still wanted a few minutes later). Prayers absorbed by a fixed event have no slot.
- **Hard cap:** pings after 22:00 are dropped, including ones a prayer slot would push past it. A ping at exactly 22:00
  still rings.
- **Already done in the app:** a block logged as done, skipped or stopped gets no more pings; a started block gets no
  start ping. This is checked when planning and again when the alarm fires (in-app logging does not reschedule).
- All of the above follow `NotificationPrefs` (`checkInsEnabled`, `mergeBackToBack`, `startOnlyUnderMin`,
  `quietDuringPrayer`, `midTaskPrayerPing`, `askFocusOnDone`, `snoozeMin`, `extendMin`).

### Prompts (newplan 12), channel "Prompts" (low importance)

- **Sunday 19:00** "Any deadlines coming up?" (opens chat). Replaces the legacy 19:00 Sunday review. Follows
  `NotificationPrefs.sundayPrompts`.
- **Sunday 12:00** "Fajr this week?" for the coming week, with an **Enter time** quick reply ("5:40", "540", "5.40am";
  02:00 to 09:59 accepted). Saved through the legacy `SettingsRepository.saveFajr` (the week's Monday), which the sleep
  coaching reads. Skipped when that week's Fajr is already saved. Also follows `sundayPrompts`. 12:00 on Sunday sits in
  the interpolated Dhuhr slot in winter, so it usually arrives at the slot end (about 12:03).
- **Night-before review** at `NotificationPrefs.nightBeforeReview` (21:30), never after the hard cap: "5-minute review:
  tomorrow", e.g. "4 blocks from 09:30 (first: PintOS), done by 19:15. 1 task not planned yet. Take 5 minutes to decide
  roughly when each thing happens." Opens the week (or approvals if proposals are pending). Replaces the legacy 23:30
  "tomorrow's plan" routine.
- **Social time goal** (2h weekdays, 3h weekends): unchanged. The existing `app/.../social` worker keeps calling the core
  `SocialNudgeState`/`SocialNudges` logic (50/80/100% nudges, 21:30 summary). Stream E does not touch it. Note that the
  worker is only scheduled from the legacy social screen (`SocialViewModel.onResume`); if Stream D drops that screen,
  something must still call `SocialScheduler.ensureScheduled()` once usage access is granted.

## Scheduling

- `CheckInScheduler.reschedule()` (bound as `CheckInRescheduler`) plans **today and tomorrow** from
  `PlannerQueries.week(...)` and `anchors(...)`, plus the next occurrence of each prompt, and diffs against what it set
  last time (kept in the `checkins` SharedPreferences). Moved blocks replace their alarms; removed or merged ones are
  cancelled; past pings are never replayed. Times are converted with `Settings.zone`.
- Called by: Stream B after every sync or apply that changes events; boot, `MY_PACKAGE_REPLACED`, time zone change,
  time change and exact-alarm permission change (forced: everything set again); app start (forced, since a force-stop
  drops alarms); after any check-in action that logs; after each prompt fires; and the legacy 15-minute routine job as a
  safety net.
- If the plan cannot be read (not signed in, store error), existing alarms are left as they are.
- `CheckInScheduler` and `CheckInActions` take `PlannerQueries` and `ProposalPort` through `Provider`, so B's planner
  service can inject `CheckInRescheduler` without a Dagger cycle.

## Permissions and setup

- `POST_NOTIFICATIONS` (Android 13+): asked by `CheckInSetupCard` (or the legacy prompt).
- Exact alarms: `SCHEDULE_EXACT_ALARM` (max SDK 32, the user grants it under "Alarms & reminders" on 12/12L) and
  `USE_EXACT_ALARM` (13+, granted at install). Without exact alarms the app falls back to `setAndAllowWhileIdle` and
  the card says check-ins may be a few minutes late. **Play policy:** `USE_EXACT_ALARM` is only allowed on Play for
  alarm/calendar-style apps. This app is sideloaded; if it ever goes to Play, drop `USE_EXACT_ALARM`, remove the
  `maxSdkVersion` from `SCHEDULE_EXACT_ALARM`, and rely on the card's link to the setting.
- Battery optimisation: one-time explainer plus a deep link to the battery optimisation list
  (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`). The one-tap `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog is not used
  (restricted permission).
- Channels: "Check-ins" (default importance), "Prompts" (low). The legacy channels of retired routines are deleted.

## Retired legacy routines

Inside Stream E's paths only (`app/.../routines/*`, `core/.../routines/*`):

- `WORK_STOPS`, `PLAN_READY`, `SUNDAY_REVIEW` are marked `retired` in the `Routine` enum: no alarms (existing ones are
  cancelled), "Send now" does nothing, their channels are deleted. Replaced by end check-ins, the night-before review and
  the Sunday prompts.
- The 15-minute job no longer replans on new sleep, materialises all-day calendar items, or runs the Monday template
  catch-up; the morning brief no longer replans or offers "Lighten today". These changed the legacy plan without an
  approved ChangeSet. The morning brief now describes today from `PlannerQueries`.
- Kept: back-to-bed (provisional readiness), morning brief, lights-out warning (sleep coaching), and the 15-minute sleep
  sync. The enum entries, `RoutineSettings`, `TomorrowPlanRepository`, `SundayReviewRepository` and `PlanNoteWriter`
  stay because legacy UI files (Stream D) still reference them; the master can delete them after integration.

## Differences from the plan

- Prompt times (Sunday deadlines 19:00, Fajr Sunday 12:00) and an on/off switch for the night-before review live in
  `NudgeSchedule` (engine/nudge) with defaults, because `NotificationPrefs` has no fields for them. Requested in
  `CHANGES.md`.
- The focus rating uses RemoteInput choices (1 to 5) because a notification shows at most three buttons.
- "Done without Start" leaves `actualStart` unknown rather than guessing the planned start.

## What to test by hand

1. Fresh install on Android 13 and 14: the card asks for notifications; exact alarms show as allowed (USE_EXACT_ALARM).
   On Android 12: the card links to "Alarms & reminders"; grant it and come back; the card row disappears.
2. With the fake planner (current bindings), open the app on a weekday: `adb shell dumpsys alarm | grep planner-checkin`
   lists today's and tomorrow's pings. Reopen the app: the list does not grow.
3. Wait for (or set the phone clock just before) a start ping: Start, Snooze 10 (comes back 10 min later), Skip.
4. End ping: Done, then pick a focus number; check the execution log (Today screen once D shows it).
5. Need 30 more / Stop here: a "Review change" notification appears; tapping it opens MainActivity with `open=approvals`;
   nothing changes in Google Calendar until approved.
6. Back-to-back blocks show one "Next up" notification; a block over Dhuhr shows "Dhuhr break, 15 min" at Dhuhr.
7. Reboot: `dumpsys alarm` shows the alarms again. Change the time zone and the time: alarms are re-set.
8. Sunday: the Fajr prompt at about 12:03 accepts "5:40" and says "Saved"; the deadlines prompt at 19:00 opens chat.
9. Battery explainer shows once; "Got it" leaves a one-line reminder until optimisation is off.
10. Social time nudges still arrive at 50/80/100% of the budget.
