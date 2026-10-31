# Redesign plan

Why: the app is not trusted enough to open daily. Three causes were named, and each traces to a specific design decision in the code. This plan fixes the causes in the order that unblocks the rest, and says how to tell it worked.

Assumption: "functioning properly" was meant as "not functioning properly".

Direction (from the owner): the app is a **companion that plans the day for you**, in the spirit of Jarvis. You check in on it; it does the work. The owner's verdict on the current app is that it has been "quite useless", so the redesign is judged by one thing: does it do something for you without you doing the planning.

## 1. What is wrong, and where it comes from

### 1.1 Calendar items and tasks are different things

The app has three separate models: tasks (movable, tickable), fixed events (immovable, not tickable, never in the task list) and, since the last release, all-day events converted into tasks by a bolt-on sync.

- A lecture at 14:00 is a `FixedEvent`: it shows on the timeline but not in Today's list, cannot be ticked, has no progress or notes.
- An all-day "PintOS (3h)" is a task with a templateKey `cal:...`, and its length and time are parsed out of its title.
- A task you type in is a third path with its own editor.

You have to know which kind a thing is before you know what you can do with it. That is the difference you are feeling, and a nicer screen will not remove it: it comes from the data model.

### 1.2 The AI cannot make an impact

- It can only move blocks that already exist (`move_block`, `remove_block`, `drop_task`) or set one task's time (`update_task`). It cannot build a day or a week, split work, or place something that has no block.
- Anything beyond "same day, within 60 minutes" needs a tap on an approval card, so most useful changes stop and wait.
- It is reactive: it runs when you open chat. The only unattended run is a 45-word, read-only note at 23:30.
- It has no memory of you (how long things really take, when you focus, what you skip) and no goals beyond the task list, so its advice is generic.
- It stops after 8 tool rounds, which is too few to plan a week.
- Nothing measures whether its changes helped, so there is no way to improve it.

### 1.3 Sleep ignores broken-up sleep

`SleepGrouping` (core/scheduler `readiness/Sleep.kt`) was written for one night of one or two blocks:

- A record starting 11:00–19:00 is a nap and is ignored, so a second sleep that starts late morning vanishes from the total.
- Records under 20 minutes are dropped, so a fragmented night loses its fragments.
- A night belongs to the date of the record's start, with a fixed 30-minute merge rule, so the Fajr wake and second sleep are patched in rather than modelled.
- Fitbit and Samsung Health can both write to Health Connect. Only overlap is handled; the preferred source is never chosen.
- The need is a flat 7 hours and readiness is "final" by clock rules (07:00 / 10:00), not by the sleep itself.

### 1.4 Underneath all three: too much to maintain

Four tabs plus chat plus settings (about 5,600 lines of UI), six notification routines, and a Sunday review. Each part asks something of you daily. If the first 10 seconds of the app do not tell you what to do now, it will not be opened.

## 2. Principles

1. **One thing, many constraints.** Every entry is an item. Differences between a lecture, a coursework block and an all-day reminder are values of fields, never separate code paths or screens.
2. **The app decides, you veto.** Changes are applied and shown as a diff you can undo (decided: apply with Undo by default, ask only before dropping something). It never asks a question it could answer itself.
3. **The first screen is a briefing, not a dashboard.** It says what the companion has done and what is next, in a few lines, and you can talk or type back.
4. **Data model before UI, UI before AI polish.** The AI and the screens both depend on the model.
5. **Measure it.** Each phase has an acceptance test and a usage metric.

## 3. Target design

### 3.1 The unified item

```
Item(
  id, title, kind-less,
  duration,                       // minutes, or null = unknown (AI/estimator fills it in)
  time: Fixed(start,end)          // hard: a meeting, an exam, a lecture you attend
      | Window(day, earliest?, latest?)  // must happen that day, optionally inside hours
      | Due(deadline)            // anywhere before this
      | Open                      // whenever
  movable: Boolean,               // fixed items are unmovable by default, anything else movable
  source: Manual | Calendar(calendarId, eventId) | Template | AI | Commitment,
  status: Open | Partial(minutesLeft) | Done | Skipped | Dropped,
  priority, module/tag, notes, links
)
```

- A timed calendar event becomes `Fixed`, `movable = false`. An all-day event becomes `Window(day)` with a default duration. A typed task is `Due` or `Open`.
- Everything is ticked, edited, progress-logged and dropped the same way. A fixed lecture can be marked "attended" or "skipped (watching the recording)", which is data the planner and the AI can use.
- **Calendar rules** replace the current selection list: per calendar, choose *Busy only* (block time, no item), *Item* (becomes an item you can tick), or *Ignore*. Lectures: the Lectures calendar is *Item* with a recording rule. Every lecture slot is online and watched, so it is never a fixed block: it becomes a "watch" item, available from the end of the slot plus the recording delay (about an hour), with a default duration of the slot length, due by the end of the week, and placed by the planner like any other work. Tutorials or anything you do attend live stay *Busy only* or become fixed items.
- Title hints (`(3h)`, `(1h, from 14:00)`) stay, but as one of several ways to fill duration and time, alongside edit-in-place and the AI's estimate.
- One list on Today and one timeline on Plan, drawn from the same query. Origin is a small badge.
- Migration: tasks, commitments and calendar-derived tasks map 1:1 into items. Fixed events are materialised as items on import, keyed by calendar event id so re-syncs update instead of duplicating. Room auto-migration plus a one-off data migration, with the backup file as a rollback.

### 3.2 Sleep as episodes, not nights

- **Episode:** any run of sleep (a record, or records merged when the gap is short). Keep every episode of any length. Short ones are stored but excluded from the "main sleep" label only.
- **Sleep day:** a 24-hour window anchored at the main wake-up, so a night and its second sleep and any nap belong together. Total = all sleep in the window; the app also shows *core* (longest episode) and *extra* (rest).
- **Interruptions:** awake stretches inside an episode and gaps between episodes, with a reason tag you can set (Fajr, toilet, couldn't sleep).
- **Sources:** your sleep comes from Google Fitbit through Health Connect, so Fitbit is the single trusted source (decided). Read `dataOrigin`; use only Fitbit records, and fall back to any other writer (such as Samsung Health) only for a night Fitbit has none. Never sum overlapping sources.
- **Readiness** uses rolling sleep over the last 24 and 72 hours against a need you set, plus timing, not "last night". It is final when the next episode is unlikely (awake for N hours or past a learned wake window), not at a fixed clock time.
- **Edit tools:** merge, split, delete or add an episode by hand, because wearable data is wrong sometimes. Edits are kept when Health Connect re-syncs.
- **Test data:** build fixtures from real exported nights (single block, Fajr split, late second sleep after 11:00, three fragments, two sources) and assert the totals. This is the acceptance test for the phase.

### 3.3 The AI becomes the planner

The AI is the product, and the rest of the app is its data and its display. It changes from answering questions to running your day, the way Jarvis does: it prepares, acts, reports and waits to be told otherwise.

- **Daily rhythm it owns:** a morning briefing (sleep, readiness, today's plan already built, what it moved and why), live repair through the day, an evening review and tomorrow's plan, applied unless you object.
- **Talk to it:** voice first ("Halaqa ran over", "I'm wiped, lighten today"), typed second. One command bar on every screen; replies are short and say what changed.
- **It acts, then tells you:** apply with Undo is the default for every change except dropping something (decided).

- **Modes:**
  1. **Plan**: build or rebuild a day or week from the items, free time, sleep and readiness; return one proposal that is a diff (adds, moves, splits, estimates, drops).
  2. **Repair**: triggered by an event (overrun, missed block, new item, poor sleep), returns a smaller diff.
  3. **Coach**: sleep and workload advice from real numbers, unchanged.
- **Richer tools:** `place_item`, `split_item`, `set_estimate`, `schedule_week(range)`, `defer_item`, `set_preference`. The scheduler stays the single validator: the model proposes, the scheduler checks hard rules and returns violations it can fix.
- **Trust ladder instead of the 60-minute rule:** per change type (same-day move, move to another day, split, estimate change, drop) choose *Ask / Apply with Undo / Never*. Decided defaults: apply with Undo for everything except drop, which asks. Each action is one transaction with one Undo.
- **Proactive:** event triggers (overrun, 15 minutes after an item should have started with no activity, new all-day item, bad night) run the Repair mode and notify with action buttons ("Moved PintOS to 16:15. Undo"). The 23:30 run becomes a Plan run for tomorrow, not a note.
- **Memory:** a small structured profile the app keeps (not free text): actual vs estimated minutes per module, hours you actually focus, items you routinely skip, preferences you set. It is sent as a compact context block every run, cached.
- **Learning loop:** log plan vs actual per item. Estimates are corrected by the app's own history before the AI sees them.
- **Rounds and cost:** raise the round limit for Plan runs, give Plan runs a bigger budget and Repair runs a small one; keep the monthly cap. Use a cheaper model for Repair, the stronger one for Plan.
- **Evaluation harness (core):** a set of replayable scenarios (week with three deadlines and a bad night; Halaqa overruns; an empty day with five all-day items) with a stub model plus recorded real runs, scoring: valid plan, deadlines met, load within cap, number of taps needed. Run in CI. This is how "impact" gets measured and prompts get improved without guessing.

### 3.4 Three surfaces

1. **Companion (home):** the briefing and the command bar. Today's changes it made with Undo, the current or next item with start and duration, remaining items below, readiness as one line. No setup cards once set up.
2. **Day / Week:** the single timeline, with items, sleep episodes shaded, free time visible. Drag to move. Tap an empty slot to add or ask the AI to fill it.
3. **Reflect:** sleep timeline (episodes, interruptions), planned vs done, social time. Read-only.

The AI is not a separate sheet: the command bar sits at the bottom of every surface ("Plan my week", "Halaqa ran over"), with voice, and suggestion chips appear inline. Settings shrinks to: Calendars (with the rules above), Sleep (source, need), AI (autonomy per change type, cost), Notifications (fewer: morning plan, repair actions, evening wind-down), Backup.

Also: a home-screen widget and a lock-screen notification showing the current item. You check in, you do not manage: the target is a glance and at most one reply a day.

## 4. Phases

| # | Phase | Delivers | Done when |
|---|---|---|---|
| 0 | **Instrument** (a few days, alongside 1) | A local, private event log (screen opened, AI change applied/undone, crash) and a one-tap "this was wrong" button that attaches the screen state. Small on purpose: the verdict on today's app is already clear. | You can see from the log whether a check-in happened each day and what you undid. |
| 1 | **Sleep model** (1–2 weeks) | Episodes, sleep day, source preference, merge/split/edit, new readiness inputs, fixtures from your real nights | For your last 14 nights the app's totals are within 10 minutes of Fitbit's own, including split and late second sleeps; Reflect shows episodes. |
| 2 | **Unified items** (2 weeks) | The `Item` model and migration, calendar rules, one list and one timeline, tick-able fixed events, scheduler works on items | No screen or tool distinguishes the source of an item except by badge; every calendar item can be ticked, edited, moved or skipped; no duplicate items after repeated syncs; backup restore round-trips. |
| 3 | **AI companion** (2–3 weeks) | Morning briefing, Plan and Repair modes, new tools, trust ladder, voice command bar, proactive triggers with action notifications, profile and learning loop, evaluation harness | The harness passes; in daily use at least 70% of proposed plans are accepted with at most one edit, and Undo is used on fewer than 1 in 5 applied changes. |
| 4 | **Three surfaces and cull** (2 weeks) | Companion / Day / Reflect, widget, trimmed settings and notifications, onboarding for a fresh install | The app opens on the briefing; first run to first plan takes under 3 minutes; the six notifications are down to three. |

Order rationale: sleep is isolated, is the thing most visibly wrong, and unblocks readiness. Items must come before the AI and the new screens, because both are built on it. Phase 0 runs alongside Phase 1.

Because the companion is the point, a thin slice of Phase 3 can ship early: after Phase 1, a morning briefing and the "apply with Undo" default on top of today's scheduler give a first visible win while Phase 2 is built. This is the one deliberate reordering, and it is optional.

## 5. Risks

- **Migration.** Merging three models into one touches tasks, blocks, the sync with the Planner calendar and backup. Mitigation: migrate behind a backup, keep the old tables for one release, add a restore test.
- **AI autonomy applied wrongly.** Apply-with-Undo can still surprise you. Mitigation: one-tap Undo on every notification, a change history, and the scheduler's hard rules stay non-negotiable.
- **Cost.** Plan runs use more tokens. Mitigation: cached profile and prompts, cheaper model for Repair, the existing monthly cap.
- **Scope.** This is a rewrite of the core. If time is short, do phases 0, 1 and 2 first: they fix two of your three complaints without touching the AI.
- **Fitbit through Health Connect.** Sync delay and background-read limits on the phone are assumptions I could not test here. Phase 1 starts by exporting your real Health Connect records, and the "waiting for sleep data" state stays.

## 6. Decisions

Made:

1. **Sleep source:** Google Fitbit, read through Health Connect. Fitbit is the single trusted source.
2. **Lectures:** all slots are online and watched, so they are flexible "watch" items, not fixed blocks.
3. **Autonomy:** apply with Undo by default; ask only before dropping something.
4. **Role:** a companion you check in on, not a tool you operate. You do little; it plans and adjusts.
5. **Why it matters:** the current app has not been useful, so the redesign has to show value in the first week of use, not after a long setup.

Open, to be settled when the new functionality description arrives:

- The exact morning and evening rhythm (what time the briefing arrives, whether you reply by voice).
- What the companion may do outside the planner, if anything (for example, add events to Google Calendar, message someone).
- Whether coursework should be broken into sessions automatically from deadlines (a goals model), or stay as items you enter.
