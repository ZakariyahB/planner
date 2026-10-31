# Planner v2: Claude Code implementation plan

Personal Android day-planner. It plans tasks into time, writes them to Google Calendar (only after approval), nudges at the start and end of each task, and has an in-app Claude for natural-language requests and replanning.

## 0. How to use this plan

1. **Audit the existing `planner` repo first.** Keep its stack and conventions where sensible. This plan assumes native Android in Kotlin; adjust names if the repo differs.
2. Build in the milestones in section 14, in order. Do not start the AI layer until the engine, approval flow and a fake calendar work end to end.
3. The scheduling engine is a **pure Kotlin module with no Android dependencies**, so it can be unit tested on the JVM.
4. Where this plan says "decision needed" (section 15), pick the stated default and leave a clear config switch.
5. Also produce `SETUP.md` (build, Google Cloud setup, sign-in, API key) and `MANUAL.md` (how to use the app) at the end.

## 1. Product summary

- **Source of truth for placement:** Google Calendar. The app does **not** render its own calendar grid. Its Today screen is a simple list with suggested times, plus a button that opens Google Calendar.
- **Source of truth for tasks:** a local database (backlog, constraints, history). Unscheduled tasks are not calendar events.
- **Every calendar write needs the user's approval.** The user is the final judge. The app can propose adding, moving, editing or removing anything in the writable calendars.
- **Deterministic engine, LLM at the edges.** Code computes times. Claude parses requests, explains, and proposes. Claude never writes to the calendar and never does time arithmetic.
- **Proactive.** Start and end check-in notifications, replanning prompts, Sunday prompts.

### What the app is for (versus plain Claude chat)

Acting without being asked (notifications), reading on-device signals (screen time, sleep), a reproducible scheduler, one-tap approval cards, and learning from the user's own history (actual vs planned durations, focus ratings). Open-ended chat is not a goal.

### Non-goals

- No custom calendar rendering.
- No separate "Planner" or import calendar. Events go straight into the user's existing category calendars.
- No app blocking (nudges only).
- No server for v1.

## 2. Architecture

```
ui (Compose)  <->  presentation (ViewModels)
                         |
   +---------------------+----------------------+
   |            |             |                 |
 engine      changeset      calendar          ai
 (pure)      (approval)     (Google API)      (Claude API)
   |            |             |                 |
   +------------+-------------+-----------------+
                         |
                  data (Room, settings)
                         |
                  notifications (AlarmManager)
```

Suggested modules: `:engine` (pure Kotlin), `:data`, `:calendar`, `:changeset`, `:ai`, `:notifications`, `:app`.

**Hard rule enforced by types:** the only code that can write to Google Calendar is `CalendarWriter.apply(approved: ApprovedChangeSet)`. `ApprovedChangeSet` can only be constructed by the approval UI path. The AI layer has no reference to `CalendarWriter`.

## 3. Calendars and routing

Resolve calendar IDs at runtime from `calendarList`, matching by **trimmed, case-insensitive name** (the Academic calendar's real name has a trailing space: `"Academic "`). Never hardcode IDs.

### Writable (with approval)

| Calendar | What belongs here | Examples |
|---|---|---|
| Academic | Coursework to produce: problem sheets, worksheets, CW, project work | P&S problem sheet, OS worksheet, ADA worksheet, CW, PintOS, SDE |
| Lectures | Lectures and tutorials caught up from recordings | Lecture - CP2, Tutorial - ADA |
| Quran and Arabic | Quran revision/memorisation, Arabic homework and revision | Arabic hw (after Asr), Quran revision |
| Commute | Flexible things done during commutes (usually all-day) | quran rev, arabic revision, kahf |
| Society | STEM Muslims and society events, Jumu'ah prep | STEMM Spotlight, Jumu'ah prep |
| Fitness | Exercise | (none yet) |
| Personal | Default for anything else, fixed personal commitments, side projects | Internship apps, Catch-up + plan next week, BoL, Quran class (Sh Anass), Halaqa, meetings, interviews, vibe coding |

### Read-only

- **Prayer times**: imported East London Mosque begin times (weekdays only, no Dhuhr on Fridays). Never written to.
- Holidays and any other calendar.

### Choosing a calendar for a new item

1. Worksheet, problem sheet, CW, PintOS, SDE, module revision -> Academic
2. Lecture or tutorial -> Lectures
3. Quran, Arabic homework or revision -> Quran and Arabic
4. Commute activity -> Commute
5. STEM Muslims or society -> Society
6. Workout -> Fitness
7. Anything else -> Personal

Ship this table as an asset (`assets/calendar-categories.md`) and include it in the in-app AI's system prompt. A copy lives in the Claude project as `claude/calendar-categories.md`.

## 4. Domain model

```kotlin
Task {
  id, title, category: CalendarCategory, durationMin,
  assignedDay: LocalDate?,          // from the user's weekly template; engine may only change it via an approved ChangeSet
  earliestStart: LocalTime?,        // e.g. lecture "recording from 14:00"
  afterPrayer: Prayer?,             // e.g. Arabic hw "after Asr"
  deadline: LocalDate?,
  splittable: Boolean = true, minChunkMin: Int = 45,
  priority: Int, kind: DEEP | PASSIVE | OVERFLOW,
  status: BACKLOG | SCHEDULED | DONE | SKIPPED,
  calendarEventIds: List<EventRef>  // linked blocks (one per chunk)
}

FixedEvent { eventRef, start, end, organiserIsExternal }   // read from calendars, not app task blocks
PrayerAnchor { date, prayer, beginTime }                   // imported, never hardcoded
Placement { taskId, chunkIndex, chunkCount, start, end, prayerBufferMin, tier }
ChangeSet { id, source, changes[], createdAt, status }
Change { type: ADD|MOVE|EDIT|REMOVE, target: EventSnapshot?, after: EventDraft?, flags[], status }
ExecutionLog { taskId, plannedStart, plannedEnd, actualStart?, actualEnd?, focus1to5?, outcome }
Settings { wakeTime=09:30, normalEnd=18:00, extendedEnd=20:00, hardCap=22:00, prayerBufferMin=15,
           minChunkMin=45, writableCalendars, notificationPrefs, claudeKeyRef, socialGoals }
```

All times are local wall-clock in the device time zone (default Europe/London). Handle the clock change (BST/GMT) correctly.

## 5. Scheduling engine (pure Kotlin)

### 5.1 Prayer model

- A prayer is a fixed **15-minute slot** `[anchor, anchor+15)`.
- `anchor` = the imported begin time for that day. **No clamping** to fixed windows (times drift over the year). Optional per-prayer offset setting, default 0.
- The Prayer times calendar only covers weekdays. For Saturday and Sunday, **interpolate** from neighbouring days (nearest weekday values, adjusted by the day-to-day trend). Mark them as interpolated in the UI and in event descriptions.
- Friday has no Dhuhr anchor (Jumu'ah is handled by a fixed event).
- A prayer whose anchor falls **inside a fixed event** (e.g. Asr during Halaqa) is absorbed: no slot, no buffer.

### 5.2 Placing one block (the core function)

```
placeBlock(desiredStart, durationMin, anchors, fixedEvents):
  start = desiredStart
  if start in any prayer slot [a, a+15): start = a+15      # tasks never start inside a prayer slot
  end = start + durationMin
  loop:                                                     # prayer buffer, to a fixed point
    n = count of anchors a with start <= a < end
    newEnd = start + durationMin + 15*n
    if newEnd == end: break
    end = newEnd
  if [start,end) overlaps a fixed event: invalid (caller tries next gap or splits)
  return (start, end, bufferMin = 15*n)
```

- A task is **one continuous block**. If its range covers a prayer, 15 min is added at the end. The block shows as e.g. 3h15 and its description says "Includes 15 min for Dhuhr".
- An anchor exactly at `end` does **not** count (`a < end`).
- The +15 can push the end across another prayer; the loop handles the cascade.
- Do not model meals.

### 5.3 Work window and tiers (all boundaries configurable)

| Tier | Window | Meaning |
|---|---|---|
| 0 | wake start (default 09:30) to Maghrib | Normal |
| 1 | Maghrib slot end to normal end (18:00) | Rest zone. Used only when tier 0 cannot fit the day |
| 2 | 18:00 to 20:00 | Extended (packed day) |
| 3 | 20:00 to 22:00 | Over the soft limit. Only if needed, and flagged on the approval card |
| - | after 22:00 | Never scheduled |

Each placement records its tier. The UI flags days that use tier 1 or higher ("uses rest time", "extended day").

### 5.4 Constraints

- `earliestStart`: block may not start before it (lecture recordings).
- `afterPrayer`: block may not start before that prayer's slot ends.
- `deadline`: warn if the plan misses it.
- Fixed events are subtracted first (classes, halaqa, Jumu'ah prep, Sh Anass class, Arabic lesson, meetings, interviews, STEM Muslims events).
- **Splitting.** Any task may split unless `splittable=false`. Minimum chunk 45 min. Prefer unsplit; split only to fit around fixed events or window limits. Chunks are titled `Name (1/2)`, `Name (2/2)`. Each chunk runs the prayer rule independently.

### 5.5 Placement order

Per day, using the user's template day assignment:

1. Place the **most constrained tasks first** (those with `earliestStart` or `afterPrayer`), each at its earliest feasible time.
2. Then fill remaining gaps from the wake start in priority order. Prefer **deep work earlier and passive tasks (lectures) later** when constraints allow.
3. Try tier 0 first, then tier 1, 2, 3. Never silently overcommit: anything that does not fit becomes an **overflow** item in the proposal.
4. Overflow goes to the Sunday catch-up block by default. Do **not** stack it onto the next day (the user's rule). Moving it anywhere else needs explicit approval.

The engine returns a proposal (list of Placements plus flags), never writes to the calendar.

### 5.6 Replanning

Trigger: user message ("halaqa ran over by 1h"), overrun tap on an end notification, or a calendar edit detected on sync. The engine re-runs from "now" with the actual state, then returns a ChangeSet (section 7). It never silently moves events.

### 5.7 Weekly rollover ("copy schedule template into next week")

Not a blind copy. Re-time:
- Shift tasks by 7 days, re-anchor to **that week's** prayer times, and respect that week's fixed events already on the calendar.
- If the target week lacks recurring fixed events the template assumed (e.g. Halaqa, Jumu'ah prep), keep those gaps open and say so in the proposal.
- Strip week tags like "w-1" from titles, or ask.
- Produce a ChangeSet for approval.

## 6. Importing existing tasks from all-day events

The user's weekly template stores flexible tasks as **all-day events** in the category calendars, numbered so they sort, e.g.:

- `1. PintOS (3h)`
- `2. Lecture - OS (2h, from 15:00)`
- `3. Arabic hw (1h, after Asr)`
- `4. Lecture - SDE (1h, Fri's)`

Parse `^\d+\.\s*(title)\s*\((duration)(,\s*(from HH:MM|after <Prayer>|...))?\)$` into Tasks (`assignedDay` = the event date, category = its calendar). Unparseable titles go to a "needs a look" list. When a task is scheduled, the proposal **edits the same event** from all-day to timed (keeps its ID and calendar, so no duplicate). Chunks beyond the first are added. Decision needed: see section 15.

## 7. Approval workflow (ChangeSet)

Every write goes through this, for any source (user request, AI, replan, rollover, notification action).

1. **Propose.** Build a `ChangeSet` of `ADD | MOVE | EDIT | REMOVE`. Show it as a diff: title, calendar, date, time (before and after).
2. **Resolve and show the target.** For MOVE, EDIT and REMOVE, resolve the request to an **exact event** (id, title, calendar, time) and show it. If a request matches more than one event, ask which. "Remove Quran class" means: find it, show it, wait.
3. **Recurring events:** the card must state scope explicitly: this instance / this and following / whole series. Default to this instance.
4. **Flags** on the card: touches a fixed event; organised by someone else (e.g. the teacher-set Arabic lesson); uses tier >= 1; over the soft limit; interpolated prayer time.
5. **Approve** all, or per change. No approval, no write. Rejected changes are dropped.
6. **Pre-flight.** Re-fetch each target. If its etag or updated time differs from the snapshot shown, mark STALE, do not write, and re-propose.
7. **Apply** via `CalendarWriter`, using client-generated event IDs for ADDs (idempotent retries) and `extendedProperties.private` tags: `planner=1`, `taskId`, `chunk`, `rev`. Tags link events to tasks. They do **not** restrict what the app may edit.
8. **Verify.** Re-read every affected event and check it matches what was approved (calendar, title, start, end). For removals, check it is gone. Report any mismatch. Mark change VERIFIED or FAILED.
9. **Undo** the last applied ChangeSet: store the inverse ops (re-add removed, revert edited). Offer it as a snackbar action.

Only events in the writable calendars can be changed. Prayer times is read-only.

## 8. Google Calendar integration

- **API:** Google Calendar REST API v3.
- **Scopes:** `calendar.events` plus `calendar.calendarlist.readonly` (narrowest that covers read/write on events and listing calendars).
- **Auth:** Credential Manager / Google Identity Services authorization on Android.
- **Token expiry (known, accepted for now):** while the Google Cloud OAuth consent screen is in "Testing", refresh tokens expire after 7 days. The user will re-sign in weekly. The app must handle expiry gracefully: show a clear "Sign in again" banner, queue nothing destructive, no crashes. Later option: publish the project as unverified production to remove the expiry.
- **Reading:** `calendarList.list` (map names to IDs), `events.list` with `singleEvents=true` over a rolling window (today -7d to +35d). Use `syncToken` incremental sync where it is workable; check the API docs for parameter restrictions with `syncToken`, and on `410 Gone` fall back to a full re-fetch. Refresh on app open, foreground, pull-to-refresh, and after every apply.
- **Prayer import:** read the Prayer times calendar; parse the description (`Begins HH:MM`) and use the event start as the anchor.
- **Detecting the user's own edits** (dragging a block in Google Calendar): on sync, compare to stored snapshots. Treat the user's edit as final, reschedule that block's notifications, and offer a replan if it breaks constraints.
- **Dev safety:** develop and test the writer against a throwaway test calendar and a fake in-memory calendar, never against real calendars first.

## 9. In-app AI

- **Claude API** (Messages API with tools). Model is a setting; default to a Sonnet-class model. Streaming for chat.
- **API key:** v1, the user enters their own key in settings, stored with Android Keystore-backed encrypted storage. Never ship a key in the APK. Later option: a small Cloudflare Worker proxy.
- **System prompt includes:** the calendar categories table and rules (section 3), the approval rules, the prayer and tier rules in plain language, and a compact summary of the current week, backlog and settings.
- **Tools the AI can call:**
  - `get_week(range)`, `get_tasks()`, `get_prayer_anchors(date)`: read-only
  - `find_events(query)`: returns candidate events with ids, never modifies
  - `propose_changes(changes[])`: sends a draft to the engine, which validates it (invariants, constraints, tiers) and returns a ChangeSet for the approval UI
  - `replan(reason, params)`: runs the engine and returns a ChangeSet
  - There is **no apply tool**.
- **The AI must not compute times itself.** It passes intent and parameters; the engine returns times.
- **Example intents:** "halaqa ran over by 1h", "remove Quran class", "push tomorrow's lectures to Thursday", "add 2h internship apps Sunday", "copy template into next week".
- Also supports lifestyle coaching (sleep, workload) in chat, using on-device signals when available.

## 10. Notifications: start and end check-ins

They are check-ins with actions, not plain alarms.

- **Start:** "Lecture - OS, 18:00" with actions **Start / Snooze 10 / Skip**. Tapping Start logs `actualStart`.
- **End:** "PintOS done?" with **Done / Need 30 more / Stop here**.
  - Done logs `actualEnd` and optionally asks for a 1-5 focus tap.
  - Need 30 more or Stop here creates a replan ChangeSet card (needs approval).
  - Logging Start and Done is **not** a calendar edit and needs no approval.
- **Defaults to avoid ping fatigue:**
  - Back-to-back blocks get one "Next up" ping instead of an end ping plus a start ping.
  - Blocks under 45 min get a start ping only.
  - No pings during a prayer slot or after the hard cap.
  - A task spanning a prayer gets one mid-task ping: "Dhuhr break, 15 min".
  - All of these are settings.
- **Android implementation:**
  - Permissions: `POST_NOTIFICATIONS` (Android 13+), exact alarms (`SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` as appropriate).
  - Use `AlarmManager.setExactAndAllowWhileIdle` for the alarms, scheduled **locally** from synced events so they work offline. WorkManager is not exact enough.
  - Reschedule on `BOOT_COMPLETED`, time zone change, time change, and every calendar sync that changes events.
  - Notification channels: check-ins (default importance), prompts (low).
  - Handle battery optimisation: show a one-time explainer and a deep link to the setting.

## 11. UI (minimal)

- **Today:** list of blocks with suggested times, tier flags, current block highlighted, Start/Done buttons. "Open in Google Calendar" button.
- **Approval card(s):** diff view, per-change approve/reject, resolved target shown, flags, "Approve all".
- **Backlog:** tasks with constraints, import from all-day events, "needs a look" list.
- **Week overview:** simple list per day with end time and tier flag (no grid).
- **Chat:** the in-app Claude.
- **Settings:** wake time, tiers, buffers, writable calendars, notification prefs, Claude key, Google sign-in status.
- Follow the user's existing visual style from the repo (see `topics/design`).

## 12. Carry over from v2

- Social media time goal: 2h weekdays, 3h weekends. Nudges only, no blocking.
- On Sundays, ask for upcoming deadlines.
- Each week, ask for rough Fajr times (used for sleep and wake coaching; wake time input defaults to 09:30; aim for about 8h after Fajr).
- No focused work after the hard cap. Night-before 5-minute review prompt (look at tomorrow, decide roughly when each task happens).

## 13. Tests

### 13.1 Engine unit tests (golden timing)

Prayer anchors for the reference week (Sat and Sun interpolated):

| Day | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|
| Mon 2 Nov | 11:49 | 14:05 | 16:35 | 18:03 |
| Tue 3 | 11:49 | 14:04 | 16:33 | 18:02 |
| Wed 4 | 11:49 | 14:02 | 16:31 | 18:00 |
| Thu 5 | 11:49 | 14:01 | 16:30 | 18:00 |
| Fri 6 | none | 14:00 | 16:28 | 17:58 |
| Sat 7* | 11:48 | 13:59 | 16:26 | 17:57 |
| Sun 8* | 11:48 | 13:57 | 16:25 | 17:56 |

`placeBlock` cases (start, duration -> end, buffer):

| Case | Start | Dur | Expected end | Buffer |
|---|---|---|---|---|
| Mon, no prayer covered | 09:30 | 2h | 11:30 | 0 |
| Mon, covers Dhuhr | 11:30 | 1h | 12:45 | 15 |
| Mon, covers Asr | 14:00 | 1h | 15:15 | 15 |
| Mon, covers Isha | 17:00 | 2h | 19:15 | 15 |
| Tue, covers Dhuhr | 09:30 | 3h | 12:45 | 15 |
| Thu, covers Maghrib | 15:00 | 2h | 17:15 | 15 |
| Fri, covers Maghrib | 15:15 | 2h | 17:30 | 15 |
| Sat, covers Dhuhr | 11:30 | 2h | 13:45 | 15 |
| Sun, covers Asr | 13:15 | 1h | 14:30 | 15 |
| Sun, covers Maghrib | 14:30 | 2h | 16:45 | 15 |
| Sun, covers Isha | 16:45 | 2h | 19:00 | 15 |

Edge cases:
- **Start inside a prayer slot:** Fri desired 14:00, Asr 14:00 -> start 14:15, 1h -> end 15:15, buffer 0.
- **Cascade:** Sat start 11:30, 2h15 -> end 14:15, buffer 30 (Dhuhr 11:48 pushes end past Asr 13:59).
- **Anchor exactly at end:** Mon start 09:30, 2h19 -> end 11:49, buffer 0.
- **Absorbed prayer:** Wed Halaqa 14:00-16:30 contains Asr 14:02: no prayer slot generated, no task may overlap the Halaqa.
- **Fixed event overlap:** a block that would run into a fixed event is invalid and the caller tries the next gap or splits.
- **No Friday Dhuhr:** no Dhuhr slot on Fridays.

### 13.2 Property tests (generate random days)

- No two blocks overlap each other or any fixed event.
- No block starts inside a prayer slot.
- `end - start == duration + 15 * (anchors in [start,end))`.
- Every chunk is >= 45 min unless the task is shorter.
- `earliestStart` and `afterPrayer` are never violated.
- No block after the hard cap.
- Output is deterministic for identical input.

### 13.3 Reference week (comparison, not strict equality)

Plan the tasks for 2-8 Nov and compare against this reference. The engine's output must satisfy all invariants; differences from the reference are acceptable if explained. Fixed events that week: BoL Wed 10:15-12:45, Quran class Thu 18:00-18:30, Arabic lesson Sun 12:15-13:15 (plus assumed Halaqa Wed 14:00-16:30 and Jumu'ah prep Fri 12:00-14:00, not on the calendar yet).

| Day | Reference blocks |
|---|---|
| Mon | 09:30-11:30 P&S problem sheet; 11:30-12:45 Lecture CP2; 14:00-15:15 Lecture P&S; 17:00-19:15 Lecture ADA |
| Tue | 09:30-12:45 PintOS; 14:00-15:15 Lecture SDE; 15:15-16:15 Arabic hw; 18:00-19:15 Lecture OS |
| Wed | 09:30-10:15 P&S CW (1/2); 12:45-14:00 OS worksheet (1/2); 16:46-17:31 P&S CW (2/2); 17:31-18:31 OS worksheet (2/2) |
| Thu | 09:30-12:45 SDE; 15:00-17:15 Lecture OS; 19:00-20:00 Tutorial ADA |
| Fri | 09:30-11:00 ADA worksheet; 11:00-12:00 PintOS (1/2); 14:15-15:15 PintOS (2/2); 15:15-17:30 Lecture P&S |
| Sat | 09:30-10:30 Tutorial P&S; 10:30-11:30 Lecture SDE; 11:30-13:45 P&S CW; 14:15-16:15 Arabic hw |
| Sun | 09:30-11:30 PintOS (1/2); 13:15-14:30 PintOS (2/2); 14:30-16:45 Internship apps; 16:45-19:00 Catch-up (overflow slot) |

Sanity totals: PintOS 8h (Tue 3 + Fri 2 + Sun 3). Days over 18:00 (Mon, Tue, Thu) are driven by recording availability, not capacity. Wed has slack.

### 13.4 Integration tests

- **Fake calendar** implementing the same interface as the Google client: approval flow, STALE detection, verify step (including a deliberately misbehaving fake that applies the wrong change), recurring-event scope, undo.
- **Title parser** tests for all-day task formats.
- **Notification scheduler** tests: reschedule on sync change, merge back-to-back pings, quiet periods.
- **AI layer** tests with a mocked Claude: the AI can only produce proposals, never writes.

## 14. Milestones

| # | Milestone | Done when |
|---|---|---|
| M0 | Repo audit, module scaffold, CI for JVM tests | Existing app builds; `:engine` module exists |
| M1 | Engine | `placeBlock`, tiers, constraints, placement, overflow; all 13.1 and 13.2 tests green |
| M2 | Data layer | Room entities, settings, title parser, execution log |
| M3 | Calendar read | Google sign-in, calendar mapping by name, event reading, prayer import and weekend interpolation, all-day task import, sync |
| M4 | Approval flow | ChangeSet model, `CalendarWriter` (only entry point), pre-flight, verify, undo; fake-calendar tests green; manual test against a throwaway calendar |
| M5 | UI | Today list, approval cards, backlog, week overview, settings |
| M6 | Notifications | Start/end check-ins with actions, local exact alarms, reschedule rules, execution log |
| M7 | In-app AI | Claude client, tools, system prompt with categories, chat UI, propose-only enforced |
| M8 | Extras and docs | Weekly rollover, Sunday and Fajr prompts, social nudges, `SETUP.md`, `MANUAL.md` |

After each milestone, summarise what changed and what to test by hand.

## 15. Decisions needed (defaults given)

1. **All-day task events after scheduling.** Default: the proposal edits the same all-day event into a timed event (no duplicates). Alternative: leave the all-day marker and add timed blocks.
2. **Tier semantics.** Default as in 5.3: normal runs to 18:00, the Maghrib-to-18:00 rest zone is used only when needed, extended to 20:00, hard cap 22:00. Confirm with the user.
3. **Dhuhr offset.** Default 0 (anchor = begin time, about 11:50). The user usually prays between 12:00 and 13:00; keep a per-prayer offset setting.
4. **Assumed fixed events** (Halaqa, Jumu'ah prep) when not yet on the calendar: default to leaving the gap open and saying so.
5. **Claude API key storage:** default to user-entered key in encrypted storage; revisit with a Cloudflare Worker proxy.
6. **Week tags** ("w-1") in template titles: default strip on rollover.

## 16. Reference: conventions used in events

- Titles: `Lecture - X`, `Tutorial - X`, `X (1/2)` for chunks.
- Descriptions: `Task: 2h. Recording available from 14:00. Includes 15 min for Asr.`, plus `Extended hours (after 18:00)` or `Uses rest zone` when relevant, and `(Sat prayer time interpolated)` where applicable.
- Tags on app-created events: `extendedProperties.private { planner: "1", taskId, chunk, rev }`.
