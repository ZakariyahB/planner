# Stream E: proactive runtime, notifications, event log

Branch `redesign/e-runtime`. Fixes: a companion that waits to be opened.

## Goal
The companion acts on its own at the right moments and tells you with actions you can tap.

## Owns
`core/.../routines/*`, `app/.../routines/*`, the event log implementation, notification channels.

## Provides
`EventLog`, trigger detection, the scheduler of runs, action notifications.

## Work
1. Trigger detection from app state: morning briefing time (after you wake, from the sleep API), evening review, overrun (an item still open past its end), missed (item past its end with no activity), new item, poor sleep.
2. Call `CompanionRunner.run(trigger)` and turn the result into a notification with actions ("Moved PintOS to 16:15. Undo", "Lighten today"). Each notification: one-tap Undo.
3. Cut the six routines to three: morning briefing, repair actions, evening plan and wind-down; migrate settings.
4. Reliable scheduling: WorkManager plus exact alarms where needed; a rescheduler that survives reboot; the 15-minute job only for sleep and calendar refresh.
5. Local `EventLog` (private, on the phone): screens opened, changes applied and undone, briefing shown, crashes; a one-tap "this was wrong" report with the screen state attached.
6. Daily "did you check in" signal for your own review.

## Acceptance
- Each trigger fires once per occurrence and not twice after a reboot or a replan.
- Notification actions work from the shade and the lock screen.
- With the companion fake, a full day of triggers produces the expected notifications (tested in core).
- The event log records an applied and undone change and survives restart.

## Agent prompt
Read `docs/REDESIGN.md` sections 3.3 and 3.4, `docs/redesign/README.md` and `contracts.md`. Implement Stream E on branch `redesign/e-runtime`, calling the companion only through `CompanionRunner` (use `FakeCompanionRunner`). Rules (full text in README.md): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.
