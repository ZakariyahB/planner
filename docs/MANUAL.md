# Using Planner

How the app works day to day. To install it, see [SETUP.md](SETUP.md).

Everything you enter stays on your phone. The only thing that leaves it is what you send to the assistant, and that is summaries (for example "slept 00:32 to 05:28 and 07:04 to 08:41"), never raw sleep data.

## The daily loop

**Morning.** When you unlock your phone after waking, you get the morning brief and your readiness score for the day. If your first sleep ended near Fajr, you may first get a "back to bed" notice saying how much sleep you have had and when to set the alarm.

**Today tab.** The date, a compact setup prompt while needed, your readiness summary, the next-task card, then the day's task list. The list follows actual scheduled dates, including work whose deadline is later. Tasks without a placement appear as **Any time**. Tick a task when it is done, tap it to edit, or tap **Log** to record progress. Long press remains a shortcut. Tap readiness for sleep details, refresh or manual entry. Use **See plan** for the calendar; Today stays a task list.

**During the day.** If something overruns, open **AI chat** above the bottom navigation ("Halaqa ran over by an hour") or drag the blocks yourself in **Plan**. Use **Adjust** in Plan to fit flexible work again. Closing chat returns to the same screen and scroll position.

**Evening.** At 21:00 (22:00 on a packed day) you get "Work stops": what got done and what moved. At 21:30 you get your social-time summary. At 23:30 you get **tomorrow's plan** to approve: switch off anything you don't want, then **Approve and sleep**, or **Ask Claude** to change it. At 23:45, thirty minutes before lights-out, you get a warning.

All of these times follow your day rules (Settings, Day rules). The numbers above are the defaults.

## Readiness

Readiness is a score out of 100 for how much load the day should carry. It is worked out from last night's sleep, not from how you feel:

| Part | Weight | What it looks at |
|---|---|---|
| Last night | 40 | Hours asleep against a 7-hour need |
| Sleep debt | 25 | How short you have been over the last 7 nights |
| Lights-out | 20 | How late you went to bed against 00:15 |
| Yesterday | 15 | How much of what was planned got done |

The score sets the load:

- **70 or more (green):** the full plan.
- **40 to 69 (amber):** about 20% lighter. This is proposed, not applied: tap **Lighten today** on Today to accept, and later replans keep it.
- **Under 40 (red):** only what is due within 48 hours.

Three things to know:

- **Provisional, then final.** After your first sleep block ends, you see "Readiness so far" and back-to-bed advice. The final score appears once your last sleep has ended and you have unlocked the phone after 07:00 (or at 10:00 at the latest). It is fixed for the day: if things overrun, the plan changes, the score does not.
- **Waiting for sleep data.** Fitbit can take a while to reach Health Connect. Until it does, Planner says it is waiting and does not score a half-recorded night. If a night never arrives, tap **Enter sleep manually** and give your lights-out and wake times.
- **Forecast.** At night, tap readiness on Today to see the score you would get tomorrow if lights-out is 00:15, and the one at your usual time.

## The Plan tab

Choose **Day**, **3 days** or **Week**. The arrows move by that much; tap the month to choose a date or return to **Today**. In day view, the week strip selects a day directly. A blue line marks now.

You see fixed commitments on neutral surfaces, prayer times as dashed lines, grey shading where you were asleep, and flexible task blocks in pale blue. Completed task blocks are neutral. Overlapping commitments share columns instead of covering each other. **All day** lists every all-day calendar event above the hourly grid; **Any time** lists relevant tasks without a scheduled block. All-day events spanning dates appear on each covered date. These items never receive a fabricated midnight time. Tap an event or task for details. Three-day and week views scroll sideways together with their headers when more space is needed.

- **Move a block:** drag it. It snaps to 15 minutes.
- **Change its length:** drag the small handle at its bottom edge.
- **Details:** tap it for **Mark done**, **Lock here** or **Remove from plan**.

Every move is checked against your rules first. If it isn't allowed (for example it lands on a prayer or after work stops) the block snaps back and a message says why. After a move that works, an **Undo** appears at the bottom of the screen.

**Locking.** A locked block (marked with a padlock) stays exactly where it is, and the planner plans everything else around it. Locked blocks can't be dragged: unlock first. Use it for things you have agreed with someone else.

**Planner calendar.** Planned blocks are copied to a Google calendar called **Planner**. If you change a time there, the app reads it back into the plan. Your other calendars are only read, never changed.

If a stretch of days has nothing planned, the tab says so and offers **Replan**.

## Tasks

**Adding.** Tap **Add task** on Today or Tasks. Enter a title and an estimate (5 minutes to 12 hours). Optional extras:

- **Days.** Pick any days of this week or next week and each one becomes its own task, due that day. Good for "OS worksheet, Monday and Wednesday".
- **Due date.** The task is due at the end of that day.
- **Do it at.** Choose a start time and the planner places the task there. If it can't (it clashes with something fixed, or lands after work stops) it plans the task at the next free time instead and tells you why on Today.
- **Priority** and **Module.**
- **Anchor.** Tie it to a prayer, with minutes before or after, for example "Asr plus 15". The task isn't planned earlier than that.

A task is always one block. It is never split. If it doesn't fit before it is due, it goes to the Sunday catch-up, never to tomorrow. Today tells you when something moved there, or "Doesn't fit this week".

**Tick, edit, drop.** The checkbox marks it done (and unticks it). Tap the task to edit, or to **Drop** it, which takes it off the plan.

**Log progress.** Press and hold a task on Today or Tasks:

- **Done** finishes it.
- **Partly** takes off the minutes you worked and keeps the rest (never less than 5 minutes). The rest is planned again at the next free time.
- **Drop** takes it off the plan.

Choose 15, 30, 45 or 60 minutes, or **Other** to type a number. A line under the buttons says what saving will do, for example "50 min left. It will be planned again at the next free time." What you log feeds the planned-versus-done chart in Insights and tomorrow's readiness.

**Weekly template.** In Settings, Weekly template, list the things that repeat, such as a lecture catch-up every Tuesday, with their days, estimate and optional time or anchor. **Use weekly template** on an empty Today, and **Generate this week** or **Next week** in the template page, create that week's tasks. Running it twice never duplicates anything.

## Talking to the assistant

Tap **AI chat** above the bottom navigation on any screen. Type, or tap **Mic** and speak: your words appear in the box for you to read and fix before you send. Close the sheet to return to where you were. If speech recognition works better offline, install an offline pack in your phone's language settings.

Things you can say:

- "Halaqa ran over by an hour."
- "Add PintOS 3 hours, due Friday."
- "Interview Thursday 14:00 to 15:00." (Becomes a locked commitment, copied to the Planner calendar.)
- "Plan my evening."
- "I did 40 minutes of the OS worksheet." (Logs progress.)
- "Why am I so tired lately?" (It looks at your actual sleep and workload.)
- "Undo that."

Small chips under a reply show what it looked at or changed, for example "Looked at your day" or "Applied a change".

**What applies by itself, and what asks you first.** The assistant never writes times into the plan directly. It proposes a change and the planner checks it against your rules first.

| The change | What happens |
|---|---|
| Moves things within the same day by an hour or less, and drops nothing | Applied at once, with **Undo** |
| Drops a task, moves it to another day, shifts something by more than an hour, or cuts the day's load | Shown as a card marked **Needs your OK**: **Approve**, **Change** or **Dismiss** |
| Breaks a rule | Cannot be approved; the card says which rule |

Everything it changes, and everything you change, is listed in **Settings, Change history**, each with **Undo**.

It coaches on sleep and on stress or workload only. It is not medical advice: for anything persistent or physical, see a GP.

**Cost.** The assistant costs a little each time you use it. Settings, Assistant shows this month's spend against the cap. When the cap is reached it pauses until next month; planning, ticking tasks and the calendar all keep working.

## Social-media time

Planner counts how long your social apps are open and nudges you. It never blocks anything.

1. Turn on **usage access** for Planner (Get set up on Today walks you to the right screen).
2. In **Settings, Social media**, choose which apps count (Instagram, TikTok, Snapchat, X, Reddit, Facebook and Threads to begin with; you can add others) and set the daily budget: 2 hours on weekdays and 3 on weekends to begin with.
3. Reflect shows today's meter alongside the weekly patterns. You get a notification at 50%, 80% and 100% of the budget, and a summary at 21:30. Each can be switched off.

## The Sunday review

On Sunday at 19:00 you get a notification, and a card on Today that stays until you open it. The review has five steps, and each can be skipped:

1. How the week went: planned against done, and what is carried over.
2. Upcoming deadlines for the next two weeks: add any that aren't on your list.
3. This week's Fajr time. A rough time is enough.
4. The weekly template, with a button to edit it.
5. **Generate and plan** for next week.

If you skip it, next week is still generated from your template on Monday, using the deadlines already on your list.

Fajr is asked once a week because it moves. Saturday uses Friday's prayer times and Sunday uses Monday's.

## Insights

The **Reflect** tab shows the last seven days: planned against done work, sleep as a timeline, lights-out against 00:15, social time against budget, and a few plain sentences summing up the week. It also shows today's social-time meter and a shortcut to your weekly review. It fills in over the first few days.

## Settings

| Page | What it holds |
|---|---|
| Day rules | Wake, work-stops, wind-down and lights-out times; buffers; the size of a packed day; the daily work cap; the Sunday catch-up |
| Weekly template | The things that repeat |
| Calendars | Connect Google Calendar, sync now, choose which calendars supply commitments |
| Sleep and Health Connect | Permission, sync, this week's Fajr |
| Social media | Apps, budget, nudges |
| Notifications | A switch for each routine, and **Send now** to test one |
| Appearance | Light, dark, or match your phone |
| Assistant | Sign in, this month's spend |
| Change history | Every change, with Undo |
| Backup and restore | See below |

Changes to day rules apply to the next plan. Blocks you have locked, or pinned to a time, stay where they are.

## Backup and restore

Your data lives only on the phone, so keep a copy.

**Save a copy.** Settings, Backup and restore, **Save to a file**. Pick a place (Google Drive or Downloads, say). The file is named `planner-backup-` and the date, and holds your tasks, plan, sleep, readiness, social time, progress log, change history and settings. It leaves out the cached copy of your calendar, which is downloaded again, and the assistant conversation unless you switch on **Include the assistant conversation**.

**Restore.** **Choose a file**, then check what the card says: when the backup was made and what is in it. **Replace** swaps what is on the phone for what is in the file, and cannot be undone. Only the parts the file contains are replaced. A file that isn't a Planner backup, or is damaged, is refused and changes nothing. After a restore, the plan is rebuilt.

A restore does not carry permissions or your Google connection. On a new phone, install the app, restore, then go through **Get set up** again.

## Troubleshooting

The app tells you what went wrong in plain words. These are the common ones.

| You see | What it means and what to do |
|---|---|
| "Google doesn't recognise this copy of the app" | The Android client in Google Cloud has the wrong package name or SHA-1. It must be `app.planner` and the SHA-1 of the keystore this build is signed with. See SETUP.md, step 2. |
| "Google turned down the calendar access" | Access ran out or was withdrawn. Tap **Reconnect** on Today, or **Settings, Calendars, Connect**. While the Google consent screen is in Testing this can happen about weekly. |
| "No connection to Google Calendar" | You're offline. Your plan is unaffected. Try **Sync now** when you're back. |
| "Your plan is saved on this phone but not in Google Calendar yet" | Copying to the Planner calendar failed; the plan on the phone is fine. The reason follows. Fix it, then press **Replan**. |
| "The assistant isn't set up in this build" | `WORKER_URL` or `GOOGLE_WEB_CLIENT_ID` is blank in `android/local.properties`. Fill it in and rebuild. |
| "Your sign-in has expired" | **Settings, Assistant, Sign in for the assistant.** |
| "Monthly AI cap reached" | The cap resets next month. To raise it, change `MONTHLY_CAP_GBP` in `worker/wrangler.toml` and run `npx wrangler deploy`. |
| "AI is busy" or "The assistant is having trouble right now" | Wait a minute or a few and send again. |
| "Waiting for sleep data" | Open Fitbit so it syncs, check sharing to Health Connect is on, and that Planner may read sleep (Settings, Sleep). Or enter last night by hand. |
| "Couldn't read sleep from Health Connect" | Open the Health Connect app, check Planner is allowed to read sleep, and tap **Refresh**. |
| Reminders arrive late or not at all | Allow **exact alarms** and notifications (Get set up on Today), and set Planner's battery to **Unrestricted** in the phone's app settings. |
| Social time stays at zero | Usage access is off, or no apps are chosen. Check Settings, Social media. |
| "Couldn't put *a task* at 14:00: ... It was planned at the next free time" | The time you chose clashed with something. The reason is given; edit the task to choose another time. |
| "Doesn't fit this week" | There isn't room before the deadline. Drop something, lower an estimate, or change a day rule. |
| "That isn't a Planner backup" | The file isn't one. Choose a file named `planner-backup-...json`. |
| "This backup was made by a newer version of Planner" | Update the app, then try again. |
| "This backup is damaged, so nothing was changed" | Use an older copy. |
| "Speech recognition isn't available" or "Offline speech isn't installed" | Type instead, or add an offline speech pack in the phone's language settings. |

If a move you made turns out to be a mistake, **Settings, Change history** lists it with an Undo.
