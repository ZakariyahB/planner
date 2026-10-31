# Stream B notes: calendar and approval

## What was built

- `engine/parse/*`: `TemplateTitles` (numbered all-day template titles), `Durations`, `Names` (prayers, weekdays, clock
  times), `CategoryRouter` (the calendar-categories table), `PrayerImport` (Prayer times events to anchors).
- `:calendar`
  - `CalendarMapping` / `CalendarDirectory`: calendars by trimmed, case-insensitive name. Writable = a category calendar
    in `Settings.writableCategories` with Google access role owner or writer. Prayer times never.
  - `PlannerService`: `PlannerQueries` + `ProposalPort` over Stream A's engine and the stores. Proposals are saved to
    `ChangeSetStore` and stop there.
  - `plan/*`: `PlanningContext` (snapshots, tasks, settings, anchors), `ChangeBuilder` (placements to changes, tags,
    flags, client ids), `Descriptions` (newplan 16), `Proposer` (every request), `TemplateImporter`.
  - `sync/CalendarSync`: rolling window today -7 to +35 days, incremental sync, user-edit detection.
  - `write/DefaultCalendarWriter`: the only writer (pre-flight, apply, verify, undo).
  - `rest/GoogleCalendarRestGateway`: Google Calendar REST v3 as pure Kotlin over `HttpTransport` and `AccessTokens`,
    so its requests are tested on the JVM.
- `:app` `gcal/*`: `UrlConnectionTransport`, `GcalAuthorizer` (AuthorizationClient), `GcalRefresh` (foreground and
  WorkManager refresh), `ZoneClock`; `di/CalendarBindings.kt` binds the real services.

## Setup

- Google Cloud: enable the Google Calendar API. The OAuth consent screen needs the scopes
  `https://www.googleapis.com/auth/calendar.events` and `https://www.googleapis.com/auth/calendar.calendarlist.readonly`
  (they replace the legacy `calendar.readonly` and `calendar.app.created`). The Android OAuth client is the same one the
  legacy sign-in uses (package `app.planner`, debug key SHA-1).
- While the consent screen is in **Testing**, Google expires the grant after 7 days: the app shows the EXPIRED state
  ("Sign in again") and writes nothing until the user signs in again. Publishing the project removes the expiry.
- The user's calendars must be named as in `docs/prompts/calendar-categories.md` (case and surrounding spaces do not
  matter). The prayer calendar must be named "Prayer times".

## For the UI (Stream D)

- Sign-in: inject `app.planner.gcal.GcalAuthorizer`. Call `authorize()`; on `NeedsConsent(sender)` launch the
  `IntentSender` (`ActivityResultContracts.StartIntentSenderForResult`) and pass the result `Intent` to
  `completeConsent(data)`, then call `PlannerQueries.refresh()`. `TodayState.auth` shows SIGNED_OUT / EXPIRED.
- `ApprovalGate.approve(...)` then `CalendarWriter.apply(...)`; the result's `ChangeResult.problem` explains STALE and
  FAILED in plain English. A STALE change is re-proposed automatically as a new pending ChangeSet ("Check again: ...").
  `CalendarWriter.undo(lastApplied)` for the snackbar; `AppliedRecord.inverse` empty means nothing to undo.
- `WeekDay.overflowCount` is the number of template tasks assigned to that day that are not on the calendar yet.

## User-facing behaviour

- Template import: numbered all-day events (`1. PintOS (3h)`, `2. Lecture - OS (2h, from 15:00)`,
  `3. Arabic hw (1h, after Asr)`, `4. Lecture - SDE (1h, Fri's)`) become tasks for that day in that calendar. Notes also
  understood: `no split`, `due Fri` / `by Fri`, times as `15:00`, `9:30`, `14.30`, `3pm`. Numbered titles that do not
  parse go to Needs a look with the reason; unnumbered all-day events (birthdays, holidays) are ignored. Re-import is
  idempotent. A bare `from 3` is not guessed (am or pm), it goes to Needs a look.
- Scheduling a template task edits that same all-day event into the first timed block (same id and calendar); further
  chunks are added as new events titled `Name (2/3)`.
- Descriptions: `Task: 2h. Recording available from 14:00. Includes 15 min for Asr. Extended hours (after 18:00).
  (Sat prayer time interpolated)`. Anything the user wrote in a description is kept below a blank line.
- "Remove Quran class": the title is resolved to an exact event; if more than one upcoming event matches, the
  assistant is asked which (`NeedsChoice`).
- Overrun ("halaqa ran over by 1h"): the halaqa is not edited; the blocks after it move. "Need 30 more" extends the
  block (prayer buffer recomputed) and moves what follows. "Stop here" shortens the block and proposes the rest as
  `Name (rest)` on the catch-up day.
- The user's own edits in Google Calendar are final. Sync compares etags with the stored snapshots (the writer stores
  the snapshot of everything it writes, so its own writes are not mistaken for the user's). A moved block that now
  clashes gets a REPLAN proposal moving the other blocks; a deleted block sends its task back to the backlog.
- Refresh: on app open, on every return to the foreground, after every apply and undo, and hourly in the background.

## What to test by hand (use a throwaway test calendar first)

**Never point the writer at real calendars first.** Create a test Google account (or rename test calendars so they
match the category names) and try everything there before using the real calendars.

1. Sign in; the calendar list loads; Prayer times is read-only; Academic matches with its trailing space.
2. Add numbered all-day events, import, check Backlog and Needs a look.
3. Plan a day; approve some changes and reject others; check in Google Calendar that only approved ones appear, the
   template event became timed (no duplicate), and descriptions and times are right (also across the clock change,
   last Sunday of October and March).
4. Edit an event in Google Calendar after a proposal is shown, then approve: it reports STALE, writes nothing, and a
   "Check again" proposal appears.
5. Undo the last apply from the snackbar; drag a block in Google Calendar, pull to refresh: the edit stays and, if it
   clashes, a replan proposal appears.
6. Wait for the 7-day expiry (or revoke access in the Google account): the banner appears, nothing crashes, nothing is
   written, signing in again recovers.
7. A recurring event: remove "this event" and check the rest of the series stays.

## Differences from the plan, and why

- The REST client lives in `:calendar` (`rest/`), not `:app`, so its request shapes (syncToken rules, 409, 410, 401,
  move, PUT) are unit tested. The app only supplies HTTP and tokens.
- Updates use GET + PUT rather than PATCH: PATCH merges `extendedProperties.private`, so undo could not take the
  planner tags off a reverted template event; PUT with the event as read keeps every field the app does not own.
- syncToken: Google forbids `timeMin`, `timeMax`, `orderBy`, `q`, `iCalUID`, `updatedMin`, `privateExtendedProperty`
  and `sharedExtendedProperty` with it, and wants the other parameters the same as the full listing (`singleEvents`,
  `showDeleted`, `maxResults`). Because the window rolls forward and a token only reports changes, the first sync of
  each day is a full re-fetch; later syncs that day are incremental; 410 falls back to a full fetch.
- Recurring scope "this and following" is refused with a clear message (it needs the series split into two); this
  instance and whole series work. Undo is not offered for whole-series changes.
- Undo of a removal re-inserts the event with its old id; Google answers 409 for a deleted id, and the gateway then
  confirms the cancelled event again, which restores it.
- Category routing refines the table's rule order where its own examples need it: "commute"/"kahf" go to Commute,
  "Lecture"/"Tutorial" beat module names (so `Lecture - SDE` is a lecture), and `Quran class (Sh Anass)`/`Halaqa`
  stay in Personal.
- Interim engine: B was first built against a small engine of its own; it now lives in test sources only and the
  proposal and writer suites run against both it and Stream A's engine. The app binds `Engine` (Stream A).
- `CalendarReader` is not bound separately; `PlannerQueries` covers the assistant's reads.
