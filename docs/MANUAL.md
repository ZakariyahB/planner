# Using Planner

How the app works day to day. To install it, see [SETUP.md](SETUP.md).

Planner plans your tasks into time, writes them to Google Calendar once you approve, and checks in at the start and end of each block. Google Calendar is where everything lives; the app shows a simple list and opens Google Calendar when you want the grid.

Three rules hold everywhere:

1. **Nothing is written to Google Calendar without your approval.** Every change, whoever proposed it (you, the assistant, a replan, a notification, next week's template), arrives as an approval card first.
2. **The app works out the times; the assistant does not.** The assistant only turns what you say into a request. The planner computes every time from your prayer times, calendar and settings.
3. **Your own edits in Google Calendar win.** If you drag a block in Google Calendar, the app takes it as final and only offers a replan if something now clashes.

Everything else stays on your phone. The only thing that leaves it is what you send to the assistant, plus summaries of your week.

## The day, prayer by prayer

The **Today** screen lays the day out in the stretches between prayers: before Dhuhr, Dhuhr to Asr, Asr to Maghrib, Maghrib to Isha, and after Isha. The heading names the stretch you are in and how long until the next prayer.

- The block happening now is the large teal card, with **Done** and **Need 30 more**. Tap any other block for Start, Done, Need 30 more, Stop here, Skip and Open in Google Calendar.
- **Done** asks how focused you were (1 to 5). This, and the start and end times, are your own log: it is not a calendar edit and needs no approval. Over time it tells the planner and the assistant how long things really take you.
- **Need 30 more** and **Stop here** ask the planner to rework the rest of the day. You get an approval card; nothing moves until you approve.
- Template items for today that have no time yet show **Plan today**.
- Banners appear only when needed: changes waiting for approval, "Sign in again" when Google access expired, and the one-time check-in setup.
- At the bottom: **Open in Google Calendar**, when the app last synced, and **Sync now**.

### How prayer time is counted

- Each prayer is a 15-minute slot starting at its begin time from your **Prayer times** calendar. No block starts inside a slot.
- A block that runs across a prayer gets 15 minutes added at its end, and its description says so ("Includes 15 min for Dhuhr"). If the extra 15 minutes push it over another prayer, another 15 is added.
- A prayer exactly at a block's end does not count.
- Friday has no Dhuhr slot (Jumu'ah is its own event). Saturday and Sunday times are estimated from the weekdays and marked with "~".
- A prayer that falls inside a fixed event (Asr during Halaqa) adds nothing.
- Settings has a per-prayer offset if you want a slot later than the begin time (default 0).

### How late it plans

| Time | What it is |
|---|---|
| Wake (09:30) to Maghrib | Normal work |
| Maghrib to 18:00 | Rest time, used only if the day cannot fit otherwise ("uses rest time") |
| 18:00 to 20:00 | Extended day, shown in amber |
| 20:00 to 22:00 | Over the soft limit, only if needed, and flagged on the approval card |
| After 22:00 | Never |

Work that does not fit goes to the **Sunday catch-up** block. It is never stacked onto the next day unless you approve that. All of these times are in Settings.

## Your weekly template

Your weekly template lives in Google Calendar as numbered all-day events in the category calendars, for example:

- `1. PintOS (3h)`
- `2. Lecture - OS (2h, from 15:00)`: a lecture recording available from 15:00
- `3. Arabic hw (1h, after Asr)`
- `4. Lecture - SDE (1h, Fri's)`

Inside the brackets: a duration (`3h`, `1h30`, `45m`, `1.5h`), then optional notes separated by commas: `from 15:00` (also `9:30`, `14.30`, `3pm`), `after Asr` (any prayer), a weekday, `no split`, `due Fri`.

**Backlog → Import from calendar** reads this week's and next week's template events into tasks for those days. Importing again updates them rather than duplicating. A numbered title the app cannot read goes to **Needs a look** with the reason. **Fix** opens it in the editor. Unnumbered all-day events (birthdays, holidays) are ignored.

When a template task is scheduled, its all-day event is **turned into the timed block**: same event, same calendar, no duplicate. If the task is split, the extra parts are added as `PintOS (2/2)` and so on. Tasks split only to fit around fixed events or the end of the day, and never into parts shorter than 45 minutes.

## Approving changes

Every proposal arrives as an approval card. A single change opens as a sheet at the bottom; several open as a list with a checkbox each.

Each card shows:

- what kind of change it is and in which calendar ("MOVE · ACADEMIC");
- the exact event it touches;
- the time before and after;
- the planner's note ("Includes 15 min for Isha");
- flags, when they apply:
  - it touches a fixed event;
  - someone else organises the event (for example the Arabic lesson your teacher set);
  - it uses rest time, an extended day, or goes over the soft limit;
  - it uses an estimated weekend prayer time;
  - it misses a deadline.

**Repeating events** ask whether to change this one only or the whole series; it starts on "this one only". "This and following" is shown but not available yet.

Approve all, or untick what you don't want. After approving, the report says what happened to each change:

- **Verified:** written, then read back from Google and checked.
- **Stale:** the event changed in Google Calendar after the card was made, so nothing was written. A fresh proposal ("Check again") appears.
- **Failed:** Google did not end up with what you approved. The reason is given.

**Undo** (on the report and in the snackbar) reverses the last approved set: removed events come back, edited ones go back to how they were, added ones are deleted.

## Week

**Week** shows each day as five bars, one per stretch between prayers. Teal is work, amber is extended, grey is fixed events. Each row also shows the Dhuhr time ("~" when estimated, "Jumu'ah" on Friday) and when the day ends, in amber if that is after 18:00. Tap a day for its blocks and flags. The arrows change week.

**Next week from your template** copies the template into next week. It is not a blind copy:

- It shifts everything by seven days and re-times it to next week's prayer times.
- It plans around whatever is already on next week's calendar.
- If a regular event the template relied on is missing (Halaqa, Jumu'ah prep), it keeps that gap open and says so.
- Week tags like "w-1" are kept unless you change that in Settings.

It all arrives as one approval list.

## Backlog

**Backlog** holds tasks with no time yet: title, duration, calendar and constraints (day, from a time, after a prayer, deadline, one block only, priority). Adding or editing a task is local and needs no approval. **Plan** proposes a time and opens the approval. New tasks are routed to a calendar by the category rules (worksheets and coursework to Academic, lectures and tutorials to Lectures, Quran and Arabic to Quran and Arabic, and so on; anything else to Personal).

## Talking to the assistant

**Ask** is the in-app Claude. Say what changed, in plain words:

- "Halaqa ran over by 1h"
- "remove Quran class": it finds the exact event, and if there are two it asks which
- "push tomorrow's lectures to Thursday"
- "add 2h internship apps Sunday"
- "copy template into next week"

Every request ends in an approval card under the reply (**Review**), never in a change. The assistant can read your week, tasks and prayer times, and it can propose. It cannot write: the app is built so that the assistant's code cannot reach the calendar writer. It can also talk through workload and sleep.

**Cost.** Requests go through your Worker, which holds the key and stops at the monthly cap. When the cap is reached the assistant pauses until next month. Planning, approvals and check-ins keep working.

## Check-ins and prompts

Check-ins are notifications with buttons, for the app's task blocks only. Classes and other fixed events never ping.

| When | Notification | Buttons |
|---|---|---|
| Block start | "Lecture - OS, 18:00" | Start · Snooze 10 · Skip |
| Block end | "PintOS done?" | Done · Need 30 more · Stop here |
| Back-to-back blocks | One "Next up" ping instead of an end and a start | Done, start next · Need 30 more · Stop here |
| A block spans a prayer | "Dhuhr break, 15 min" | none |

- Blocks under 45 minutes get a start ping only.
- A ping that would land in a prayer slot waits until the slot ends.
- Nothing pings after 22:00.
- A block you already logged in the app does not ping again.
- **Need 30 more** and **Stop here** give you a "Review change" notification that opens the approval.

**Prompts** use a quieter channel:

- **Night-before review**, 21:30: tomorrow's blocks at a glance and anything not planned yet.
- **Sunday 19:00**: "Any deadlines coming up?" (opens Ask).
- **Sunday 12:00**: "Fajr this week?", with a quick reply. A rough time is enough; it is used for sleep and wake advice (about 8 hours after Fajr).
- **Social time**: nudges at 50%, 80% and 100% of your budget (2h on weekdays, 3h at weekends to start with), and a 21:30 summary. Nothing is ever blocked.

Every rule and time above is a setting.

## Sleep and readiness

Sleep comes from Fitbit through Health Connect. Fitbit's records are trusted. Another app's records are used only for a day Fitbit has none, and two sources are never added together.

- **Sleep day.** Every stretch of sleep counts, however short and whenever it starts. A night broken by Fajr, a second sleep that starts late morning, and a nap all belong to the same sleep day, which is built around your main wake-up. You see the total, the longest block (core), the rest (extra) and each time you were awake in between.
- **Readiness** scores your sleep over the last 24 and 72 hours against your sleep need (7 hours to start with; set it in Settings), plus your recent sleep debt, how late lights-out was and how much of yesterday got done. It becomes final once another sleep is unlikely: after a few hours awake, or once you are past your usual wake-up. It no longer goes by a fixed clock time. Until then it says "so far", or "waiting for sleep data".
- Readiness is advice for you and the assistant. It never changes the plan by itself.

**Reflect** (from Settings or the bottom of Week) shows the last week: planned against done, sleep, lights-out and social time.

## Settings

| Group | What |
|---|---|
| Google | Sign-in status, Sign in / Sign in again, Sync now |
| Check-ins | Notifications, exact alarms, battery (one-time setup) and every check-in rule |
| Your day | Wake, normal end, extended end, hard cap; prayer slot length and per-prayer offsets; smallest chunk; recording delay; catch-up day |
| Calendars | Which category calendars the app may write to (with approval) |
| Template | Turn all-day items into the timed block; keep or strip week tags |
| Prompts | Night-before review, Sunday deadlines, Fajr prompt day and time |
| Sleep | Sleep need; Health Connect |
| Assistant | Model |
| Appearance | Match my phone, light or dark |
| More | Reflect, Social time, Backup and restore |

## Backup and restore

**Settings → Backup and restore** saves and restores the older app data (sleep, readiness, social time, settings) as before. Tasks, approvals and the check-in log for v2 live in a separate database on the phone (`planner_v2.db`). Your calendar is in Google Calendar anyway, and re-importing the template rebuilds the tasks.

## Troubleshooting

| You see | What it means and what to do |
|---|---|
| "Sign in again" on Today | Google access expired (every 7 days while the Google Cloud consent screen is in Testing). Tap it. Nothing was written meanwhile. To stop this, publish the consent screen (SETUP.md, step 2). |
| "Google doesn't recognise this copy of the app" | The Android OAuth client needs package `app.planner` and the SHA-1 of the key this build is signed with (SETUP.md, step 2). |
| A change shows **Stale** | The event changed in Google Calendar after the card was made. Nothing was written; review the "Check again" proposal. |
| A change shows **Failed** | Google ended up different from what you approved. The reason is shown. Check the event in Google Calendar, then Undo or ask for a replan. |
| "Calendar is read-only" | The event is in a calendar the app may not write to (Prayer times, or one switched off in Settings → Calendars). |
| A template item is in **Needs a look** | The title is numbered but did not parse. Tap **Fix**, or rename it in Google Calendar (for example `3. Arabic hw (1h, after Asr)`) and import again. |
| Weekend prayer times look slightly off | Weekends are estimated from the weekdays ("~"). Use the per-prayer offset if needed. |
| The assistant says the model isn't allowed | The model in Settings is not one the Worker knows. Use the default, or update and redeploy the Worker. |
| "Monthly AI cap reached" | It resets next month. To raise it, change `MONTHLY_CAP_GBP` in `worker/wrangler.toml` and run `npx wrangler deploy`. |
| Check-ins arrive late or not at all | Allow notifications and exact alarms (the setup card on Today), and set Planner's battery to **Unrestricted**. |
| "Waiting for sleep data" | Open Fitbit so it syncs, and check that its Health Connect sharing is on and that Planner may read sleep. |
| Social time stays at zero | Usage access is off, or no apps are chosen (Settings → Social time). |
