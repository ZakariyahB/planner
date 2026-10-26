# Day Planner v2: build plan (for Claude Code)

## Context

ZB is a 2nd-year Imperial Computing student with a full week: online lectures and tutorials (recordings come out about 1h after each live slot), coursework (PintOS, Prob & Stats, OS, ADA, SDE), Arabic study, prayer-anchored routines, BoL, Halaqa, Sh Anass and society work.

Four weeks of his Fitbit data show the real problem is sleep timing, not missing tools:
- median lights-out is 01:27 against a 00:15 target, about 6h total sleep;
- he wakes at Fajr, then the second sleep runs until 09:30–10:30;
- the day's plan collapses when something overruns.

He wants a **personal Android app** (single user, installed from Android Studio) that:
1. holds his data locally;
2. builds a realistic plan with **suggested times**, shown as a list and as a **Google Calendar-style timeline**;
3. has an **in-app AI (Claude)** for planning plus sleep and stress/workload coaching, that replans when things change ("Halaqa ran over by 1h");
4. sets the day's load from a **readiness score** (sleep);
5. tracks **social-media time** against a budget (2h weekdays, 3h weekends) with **nudges only**, no blocking.

He chose to go straight to v2 instead of v0→v1 first. The plan offsets that risk by making the app usable from M2 onward.

Design reference: the "Day Planner v0 — interface & journey" canvas. Its v2 boards are V2System, V2Today, V2Plan and V2Block. V2Block is now replaced by the social-time nudges, because he chose nudges only.

## Decisions (from Q&A)

| Topic | Decision |
|---|---|
| Codebase | New monorepo `planner` (GitHub, private) |
| Install | Run from Android Studio onto his phone; no Play Store |
| Calendar | Read **all** his Google calendars; write timed blocks **only** to a new `Planner` calendar; read back edits made there |
| Task sources | **Tasks come from him only**: in-app entry (text/voice/chat) + the weekly template + the **Sunday check-in asking for upcoming deadlines**. Google Calendar is read **only for fixed commitments** (classes, meetings, interviews, BoL, Halaqa…), never imported as tasks |
| Scheduling | Suggested start/end per task; **no splitting**, each task is one block |
| Prayer times | Weekdays: from his `Prayer times` calendar (Dhuhr/Asr/Maghrib/Isha). **Fajr: asked weekly on Sunday** (one rough time per week). Weekends: no prayer events; anchors like "after Asr" use the nearest weekday's time |
| AI autonomy | **Small moves auto-apply with Undo** (same-day shift ≤ 60 min). Asks first before dropping a task, moving it to another day, or cutting the load |
| AI coaching scope | Sleep + stress/workload only. Not medical advice |
| AI input | Text + voice (on-device speech-to-text) |
| AI budget | ~£10–15/month, enforced by the server |
| Social time | Social-media apps only; budget 2h weekdays / 3h weekends; notifications at 50/80/100% + daily summary; no blocking |

## Architecture

```
Android app (Kotlin, Jetpack Compose, Hilt, Room, WorkManager + AlarmManager)
 ├─ Room DB (source of truth for tasks, blocks, sleep, readiness, logs, chat)
 ├─ Scheduler (pure Kotlin, deterministic): places tasks, validates every change
 ├─ Integrations: Google Calendar API (Authorization API, OAuth on device)
 │                Health Connect (sleep sessions; Fitbit writes into it)
 │                UsageStatsManager (social-media minutes)
 │                SpeechRecognizer (on-device voice → text)
 └─ AI agent loop runs ON DEVICE: tools execute against Room/Scheduler
        │  HTTPS (Google ID token)
        ▼
Cloudflare Worker `planner-proxy` (TypeScript)
 ├─ verifies Google ID token: email must be bello.zakariyah20@gmail.com (config var)
 ├─ adds ANTHROPIC_API_KEY, forwards to the Claude Messages API
 ├─ routes models: chat/replan → claude-sonnet-5-5 ; scheduled runs → claude-haiku-5-5
 └─ monthly spend cap in KV (token-count × price table) → 402 when exceeded
```

Rules: the **LLM never writes times directly**. It calls `propose_changes`, the Kotlin Scheduler validates the change and classifies it as small or big, then it is applied or shown for approval. The Worker stores token counts only, never message content. Only summaries leave the phone (e.g. "slept 00:32–05:28 + 07:04–08:41"), never raw sleep-stage data.

## Repo layout

```
planner/
  android/            Gradle project, module :app (+ :core:scheduler pure-Kotlin module for fast JVM tests)
  worker/             Cloudflare Worker (wrangler, vitest)
  docs/               PLAN.md (this file), SETUP.md, MANUAL.md, prompts/
  .github/workflows/  android-ci.yml (assemble + unit tests), worker-ci.yml
```

## Data model (Room)

- `Task(id, title, module, estMin, availableFrom?, anchor? {prayer, offsetMin}, dueAt?, priority, source {manual|chat|template|sunday}, status {open|done|partial|dropped})`
- `Block(id, taskId?, date, start, end, kind {task|fixed|prayer|sleep|buffer}, locked, gcalEventId?, origin {scheduler|ai|user})`
- `FixedEventCache(gcalId, calendarId, title, start, end, allDay)`: refreshed from Google
- `SleepSession(id, start, end, asleepMin, deepMin, remMin, blockIndex {1|2}, source)`
- `Readiness(date, provisional?, final?, forecastIfOnTime?, forecastIfLate?, inputsJson)`
- `DayLog(date, taskId, plannedMin, actualMin, outcome, note)`
- `ChangeLog(id, ts, actor {ai|user|scheduler}, diffJson, undone)`: powers Undo
- `FajrWeek(weekStart, time)` · `WeeklyTemplate(json)` · `Settings(key, value)`
- `SocialUsage(date, minutes, perAppJson)` · `ChatMessage(id, ts, role, content, toolJson?)`

## Scheduler (`:core:scheduler`, pure Kotlin, heavily tested)

Inputs: day window, fixed events, prayer times, tasks, readiness band, existing locked blocks.

Defaults (all editable in Settings):
- wake 08:45;
- focused work stops 21:00, or 22:00 on "packed" days (≥ 3h of fixed events);
- lights-out 00:15, with a 23:30 wind-down;
- Fajr from `FajrWeek`;
- 15-min buffer after each block;
- 15-min prayer buffers (Dhuhr/Asr/Maghrib/Isha);
- **Friday after 17:00 kept clear**;
- Sunday catch-up block of 2h.

Placement:
- earliest-deadline-first, then priority;
- respect `availableFrom` (recording times) and `anchor` (e.g. Asr + 15);
- never split a task;
- when readiness is amber or red, put the hardest task first.

Overflow: a task that doesn't fit goes to Sunday catch-up. It is never stacked onto tomorrow.

Readiness bands set the load:
- 70+: full plan;
- 40–69: total load −20% (proposed, not auto-applied, because it counts as a big change);
- under 40: only tasks due within 48h.

API:
- `plan(day)`;
- `validate(changeSet) → {ok, violations[], size: SMALL|BIG}`;
- `apply(changeSet)` → writes a ChangeLog entry.

SMALL means every op is a same-day move of ≤ 60 min, with nothing dropped and no change of day.

## Readiness (on device)

- **Provisional:** after the first sleep block ends near Fajr. Recommends how long to go back to bed (e.g. "3h 30m so far → back to bed, alarm 08:45").
- **Final:** once the last sleep session has ended and he unlocks the phone after 07:00 (fallback 10:00). This sets the day's load. It is fixed for the day; overruns trigger a replan, not a rescore.
- **Forecast:** at 23:30, shows the score for lights-out at 00:15 vs at his 7-night median time.
- **Inputs (heuristic v1, weights in one file):**
  - last night's sleep vs a 7h need;
  - 7-night sleep debt;
  - lights-out vs the 00:15 target;
  - yesterday's planned vs done.
- No HRV for now.
- If Health Connect data is late, show "waiting for sleep data". Never score an incomplete night.

## AI (agent loop on device)

- System prompt in `docs/prompts/system.md`, using prompt caching. It contains:
  - his rules (above);
  - coaching scope: sleep and stress/workload, in practical, plain-language advice;
  - limits: not medical advice; suggest a GP for anything persistent or physical; no diagnoses;
  - tone: concise, honest, challenges bad plans.
- Tools (JSON schemas in `docs/prompts/tools.json`):
  - `get_day(date)`
  - `get_tasks(filter)`
  - `get_sleep(nights)` (summaries)
  - `get_readiness(date)`
  - `get_social_time(days)`
  - `propose_changes(ops[])` → validator result + size
  - `apply_changes(proposalId)`: allowed only when SMALL, or after the user taps Approve
  - `add_task(...)`
  - `log_progress(taskId, minutes, outcome)`
  - `undo(changeId)`
- Example: "Halaqa ran over by 1h" → `get_day` → extend the Halaqa block → re-fit the remaining tasks → small moves apply immediately with an Undo snackbar; tasks moved to Sunday show as a proposal card.
- Voice: `SpeechRecognizer`, preferring offline recognition. The transcript goes into the chat box before sending.
- Scheduled AI runs, on the cheaper model:
  - 23:30 draft of tomorrow's plan + forecast;
  - morning brief;
  - Sunday review.

## UI (Compose, Material 3, simple and sleek)

- **Palette:** black/white neutrals (light and dark mode); **blue** as the single accent for primary actions, selection and the "now" line; **green** for done, on-track and good readiness. Amber is used sparingly, only for low readiness and over-budget warnings.
- **Typography and shape:** one sans family (Inter, or Roboto as the system fallback), tabular figures for times, generous whitespace, thin dividers, flat surfaces, 12 px corners, no heavy shadows or gradients.
- All colours and fonts live in one `Theme.kt` token file, so the look can be changed in one place.

- **Today:** readiness card, numbered task list with suggested times and checkboxes, social-time meter, and a chat entry with a mic button.
- **Calendar:** Day / 3-day / Week timeline in the style of Google Calendar.
  - Fixed events in their calendar colours, prayer markers, Planner blocks.
  - Drag to move, snapping to 15 min; long-press to resize.
  - Every drag goes through `validate`, and a violation snaps back with a message.
  - A "now" line.
- **Plan:** tomorrow's plan approval sheet (as on the V2Plan board): toggles, "Approve and sleep", "Ask Claude".
- **Chat:** the full conversation, tool-activity chips, proposal cards with Approve/Change, Undo.
- **Insights:**
  - sleep timeline;
  - lights-out vs target;
  - planned vs done;
  - social time per day vs budget;
  - weekly review.
- **Settings:**
  - day rules;
  - list of social apps (default: Instagram, TikTok, Snapchat, X, Reddit, Facebook, Threads);
  - budgets;
  - notification toggles;
  - which calendars to read;
  - AI spend this month.

## Routines and notifications (AlarmManager exact alarms for timed ones; WorkManager for syncs)

| When | What |
|---|---|
| End of first sleep block (Health Connect change) | Provisional readiness + back-to-bed advice |
| Phone unlocked after final wake | Morning brief + final readiness; low readiness → proposal to cut the load |
| Every 15 min (WorkManager) | Social usage refresh; notify at 50/80/100% of budget |
| 21:00 (22:00 packed) | "Work stops": done count, what moved |
| 21:30 | Social-time daily summary |
| 23:30 | Tomorrow's plan ready (AI draft) + forecast |
| 23:45 | Lights-out in 30 min |
| Sunday 19:00 | Review flow: planned vs done → **ask for upcoming deadlines** → **ask for this week's Fajr time** → suggested template tweaks → generate next week |

Each routine has its own notification channel, so he can mute any one.

## Google Calendar (fixed commitments only)

- Timed events on the calendars he selects become **fixed blocks** the scheduler works around: classes, meetings, interviews, BoL, Halaqa, Sh Anass, the Arabic lesson. Synced on open, every 30 min and before every plan or replan.
- All-day events are ignored for planning. They can be shown as a strip at the top of the day for context.
- **Calendar picker in onboarding and Settings.** By default, `Lectures` is unticked as fixed, because he watches recordings rather than attending live. The app's own `Planner` calendar is always excluded from the fixed set.
- Nothing is imported as a task. He adds tasks himself (in-app, by voice, or by chat, e.g. "add PintOS 3h, due Fri"). Recurring ones, like weekly lecture catch-ups, live in the in-app weekly template.

## Configuration contract (the setup guide depends on these exact names)

- Android `applicationId`: `app.planner`. Build reads `android/local.properties` (git-ignored): `GOOGLE_WEB_CLIENT_ID`, `WORKER_URL`.
- Google OAuth scopes: `openid`, `email`, `https://www.googleapis.com/auth/calendar.readonly`, `https://www.googleapis.com/auth/calendar.app.created` (create and edit only the app's own `Planner` calendar).
- Worker name `planner-proxy`.
  - `wrangler.toml` `[vars]`: `ALLOWED_EMAIL`, `GOOGLE_WEB_CLIENT_ID` (the ID token's `aud` must match), `MONTHLY_CAP_GBP = "15"`.
  - KV binding `USAGE`. Secret `ANTHROPIC_API_KEY`.
  - Routes: `GET /health` → `ok`; `POST /v1/messages` → 401 without a valid token, 402 when over the cap.
- The app shows Worker errors in plain words: 401 → "AI unavailable (401)", 402 → "monthly AI cap reached (402)". Planning, ticking and the calendar keep working without the AI.

## Behaviours the user manual promises (build these)

- **Lock** a block (long-press → Lock): the scheduler keeps it and plans around it.
- **Settings → Change history:** a list of every ChangeLog entry, each with Undo.
- **Settings → AI spend:** this month's total against the cap. The Worker returns usage in a response header; the app sums it locally.
- **Manual sleep entry** on the morning card when Health Connect has no data.
- **Commitments added by chat** ("Interview Thu 14:00–15:00") become locked fixed blocks, mirrored to the Planner calendar.
- **Sunday check-in skipped:** the next week is still generated from the template and known deadlines. The check-in card stays on Today until it's opened.
- **Weekend anchors:** Saturday uses Friday's prayer times, and Sunday uses Monday's.
- **Long-press a task on Today** to log partial progress.

## Milestones (each ends with a usable build)

1. **M0 Scaffold:** monorepo, Gradle with version catalogue, Hilt, Room, Compose theme, CI; Worker skeleton with `wrangler dev` and the ID-token check.
2. **M1 Data in:** Google sign-in + Calendar read (fixed events, calendar picker); add/edit tasks; weekly template; Today list (no times yet).
3. **M2 Scheduler + Calendar:** `:core:scheduler` with tests; Day/3-day/Week timeline; drag/resize with validation; write to and read back the Planner calendar. **Usable daily from here.**
4. **M3 Sleep + readiness:** Health Connect read (incl. background permission where available); split-sleep grouping; provisional/final/forecast; weekly Fajr input.
5. **M4 AI:** Worker proxy with spend cap + model routing; on-device tool loop; chat UI + voice; SMALL/BIG autonomy; Undo.
6. **M5 Routines:** all alarms and notifications; 23:30 plan sheet; Sunday review flow.
7. **M6 Social time:** usage-access onboarding, per-app minutes, budget nudges, Insights chart.
8. **M7 Polish:** Insights screens; JSON export/import backup to a file; Settings; empty and error states.

## Verification

- **Scheduler unit tests** (JVM), written from his real week as fixtures:
  - Mon 26 Oct;
  - Wed 28 Oct with BoL 10:15–12:45 and Halaqa 14:00–16:30;
  - "Halaqa +60 min" → the OS worksheet still fits before 21:00 and the CW remainder goes to Sunday;
  - Friday after 17:00 stays empty;
  - Sat "after Asr" uses Friday's Asr.
- **Calendar mapping tests:** timed events become fixed blocks; all-day events are ignored; deselected calendars (e.g. Lectures) are ignored; Planner calendar events are never treated as fixed.
- **Readiness tests** on anonymised fixtures shaped like his Takeout data: 3h33m single block; 4h42m + 2h10m split; a slept-through night.
- **Worker tests** (vitest + Miniflare):
  - a request with the wrong email is rejected;
  - the cap returns 402;
  - model routing is correct.
- **Manual end-to-end on the phone:**
  - add this week's tasks by voice and chat;
  - drag a block in the app and see it in Google Calendar;
  - say "Halaqa ran over by an hour" by voice and get Undo plus a proposal;
  - approve the 23:30 plan;
  - check the social notification fires at 50%.

## Deliverables after approval (made by Claude in chat, not Claude Code)

1. **This plan** as `docs/PLAN.md`, a file to hand to Claude Code.
2. **Setup guide** (Docs):
   - Android Studio + USB debugging;
   - Google Cloud project: Calendar API, OAuth consent in Testing mode with himself as a test user, Android client with debug SHA-1, Web client ID for the ID token;
   - Health Connect + turning on Fitbit sharing;
   - Anthropic Console: API key + monthly spend limit as a second safety net;
   - Cloudflare: wrangler login, secrets, deploy;
   - first build and install; granting permissions (usage access, notifications, exact alarms, Health Connect); first-run onboarding.
3. **User manual** (Docs): the daily loop; reading readiness; calendar views and dragging; talking to the AI (example phrases); what auto-applies vs asks; social time; the Sunday review; troubleshooting.

## Risks to watch

- **OAuth Testing mode** can expire Google tokens about every 7 days for sensitive scopes like Calendar. Check during M1. If it happens, switch the consent screen to "In production" unverified; a warning screen is fine for single use.
- **Fitbit → Health Connect:** confirm sleep sessions arrive, and how late, during M3. Fallback: manual sleep entry.
- **Exact alarms and battery optimisation** on his phone model. The setup guide covers exempting the app.
- **Building replaces using:** keep the current Google Calendar routine until M2 is in daily use.
