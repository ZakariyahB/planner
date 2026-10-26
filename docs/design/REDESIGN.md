# Planner — complete redesign concept

Designed and implemented 26 October 2026. The Android UI now follows this direction: neutral surfaces with blue highlights, four destinations, daily tasks on Today, the calendar in Plan, and an AI chat sheet available throughout the app. Existing repositories, scheduling rules, permissions, routine flows and approval logic remain the source of truth. The separate interactive HTML concepts use illustrative data; their connections, AI calls, file operations and schedule generation are simulated.

The HTML prototype supports navigation, task add/edit/complete, partial progress, scripted coach proposals, applying a change and undoing it, appearance selection, reviews and recovery scenarios. Some secondary prototype preference forms demonstrate saving without implementing persistence. The Android implementation uses real state and storage, not the prototype's scripted data.

## Product direction

Make the next useful action obvious. Let the user capture work, see where it fits, make progress and adjust when life changes without moving between disconnected workflows.

The proposed identity is calm and practical: plain white and grey surfaces, blue primary actions and selected navigation, pale blue flexible task blocks, neutral markers for fixed commitments, generous spacing and a serif heading paired with a readable sans serif. Light, dark and system appearance share the same hierarchy. Content and labels distinguish status alongside colour.

Two complete, navigable directions are included in the interactive concept:

- **Focus first**: Today highlights one upcoming task, with a visible progress action and a short agenda beneath it. Recommended starting direction for reducing daily decision load.
- **Agenda first**: Today pairs a compact next-task card, about half the height of the Focus first card, with the sequence of commitments, prayer and flexible work. Suitable for days with many time-bound events. Cards use the selected 17px corner radius. The remainder of the app follows the same design system.

## Information architecture

| Destination | Purpose | Primary actions |
| --- | --- | --- |
| Today | Decide what to do next | Log progress, inspect readiness, review the rest of the day, close the day |
| Plan | Understand and adjust time | Day / 3 days / week, inspect a block, lock a block, adjust the plan, review tomorrow |
| Tasks | Capture and organise commitments to yourself | Add, edit, complete, log progress, filter, open recurring tasks or catch-up |
| Reflect | Learn from the week | Planned versus completed, sleep, lights-out, social time, weekly review |
| Coach sheet | Get help in the context of the current screen | Explain an overrun, review a lighter plan, approve a proposal, undo |
| Your space | Configure personal rules and connections | Rhythm, integrations, preferences, history, backup |

The coach is a contextual sheet accessible above the navigation on each screen. It replaces the separate Ask destination while retaining a continuous conversation. Closing it returns to the same screen. A global add button opens the same task editor everywhere.

## Daily experience

### Morning and Today

Show the date, a short headline, a compact readiness row and the next task. Readiness opens its explanation rather than occupying several home cards. Show missing data as “Waiting for sleep data”, with manual entry available. A lower readiness score suggests reviewing a lighter plan; it does not silently cut the load.

Under the next-task card, Today shows a task list with completion checkboxes, scheduled start times or an “Any time” label, and progress actions. “See plan” opens the calendar in Plan. Sunday catch-up stays separate even when a task is due soon. Completion, edits and approved plan changes remain shared between Today and Plan. Tasks also has an explicit **Today** filter alongside the full backlog filters.

Keep recovery messages local: an expired calendar connection produces one reconnect message while the saved plan stays usable. Setup should be resumable and disappear when completed. Social time and deeper sleep data remain one step away through Reflect or Your space.

### Tasks and progress

Adding requires a title and estimate. Module, priority, due date, preferred start, selected days and prayer anchors are progressively disclosed. Estimates stay within the existing 5–720 minute range. A selected day produces a distinct task for that day; the concept shows the controls, while generation remains simulated.

Completion is a visible checkbox. Logging progress is an explicit action, also available from task details, so users do not have to discover a long press. Done, Partly and Drop explain what will happen before saving. Partial progress reduces the remaining estimate, with the existing five-minute minimum, and the production scheduler should place the remainder in the next allowed slot.

### Planning and adjustment

Fixed events, flexible work, prayer boundaries, sleep and the work cutoff appear in one schedule. Inspecting a task exposes timing, locking and removal. Fixed calendar commitments explain their source and read-only status. The concept demonstrates a duration-proportional day calendar, overlapping event lanes, day / three-day / week navigation and task sheets; a production calendar should additionally retain drag-to-move, resize handles and 15-minute snapping, alongside accessible time inputs.

Above each day’s timed events, **All day** lists calendar events without start times and **Any time** lists tasks assigned to that day without a fixed start. These items appear in day, three-day and week views. Multi-day all-day events appear on each covered date; date-specific deadlines appear only on their date. Neither type receives a fabricated midnight slot or fills the entire hourly grid. Untimed task completion is shared with Today, while Sunday catch-up remains a distinct group.

All schedule changes must continue through the existing scheduler. Keep the current rules: one unsplit block per task, fixed events and prayer boundaries protected, locked blocks respected, explicit placement conflicts explained, and work that cannot fit before its deadline sent to Sunday catch-up rather than silently moved to tomorrow.

The “Adjust” sheet starts with intent: something ran over, the day needs lightening, or flexible work needs a fresh fit. The coach already knows the selected day and task context.

### Coach and trust

Use plain responses and compact before/after change cards. Make the affected tasks, times, catch-up moves and stopping time visible before approval. Preserve the existing approval policy: small moves can apply with Undo; dropped work, cross-day moves, shifts greater than an hour and reduced load require approval. Rule-breaking proposals cannot be applied.

The concept demonstrates a larger afternoon adjustment: move PintOS from 14:00 to 15:00 and put reading into Sunday catch-up. Applying updates the sample Plan and adds a history entry. Undo restores the previous sample state. This is a scripted interaction, not a live AI conversation or a scheduler validation result.

Voice input is reviewed as text before sending. The concept inserts an example transcript rather than recording audio. A reached AI allowance explains when the coach returns and leaves ordinary planning available.

### Evening and tomorrow

“Close the day” opens a gentle recap and a path to tomorrow. Tomorrow’s sheet lists optional tasks and protects fixed commitments. The user can approve the plan or ask for a change. Production should show the approved selection and plan consistently across Today, Plan and routines. The concept illustrates the selection and approval interaction; it does not generate a second real schedule.

### Reflection and weekly review

Reflect starts with planned versus completed work and a short interpretation, followed by sleep, social time and lights-out. Keep measures grounded in actual records, with meaningful empty states while the first week accumulates.

The five-step weekly review preserves the current workflow: review the week, check deadlines, set Fajr, inspect recurring tasks, then generate next week. Each step can be skipped. Generation must remain idempotent in production.

## Your space

| Group | Screens and content |
| --- | --- |
| Your rhythm | Day rules; packed-day exceptions; buffers; daily focus cap; catch-up window; recurring tasks and prayer anchors; routine notification preferences |
| Connections | Calendar connection and source selection; Health Connect and manual sleep; Fajr; social apps, budgets and nudges |
| Preferences and data | Coach account and monthly allowance; light / dark / system; change history; backup and restore |

Backup defaults to excluding coach conversation. Restore shows a file summary, explicitly states replacement cannot be undone, and requires a final replacement action. Actual file selection and restoration are outside this concept. Account and permission flows must use native Android / Google surfaces in implementation.

## Screen and state coverage

The concept includes Today, Plan, Tasks, Reflect, Your space, Day rules, Weekly template, Routines, Calendars, Sleep, Social time, Coach settings, Appearance, History, Backup, onboarding and a recovery-state explorer.

Sheets include task add/edit, progress, readiness, plan adjustment, contextual coach, a before/after proposal, fixed event details, manual sleep, tomorrow, evening recap, catch-up, recurring task editing, privacy, restore preview and confirmation, sleep details, Fajr, packed-day rules, catch-up settings, social app selection, approval policy, account, date selection and a notification preview. The weekly review has five steps.

Recovery examples include expired calendar access, missing sleep data, lower readiness, no tasks and exhausted AI allowance. Each should lead to a concrete next action and preserve usable local features.

## Interaction and accessibility requirements

- Maintain Android Back behavior: dismiss a sheet, return from a detail page, then navigate back through the app.
- Retain screen and scroll position when the coach closes. Restore focus to the triggering control.
- Use at least 48dp touch targets in the native implementation. Support TalkBack descriptions, font scaling and reduced motion.
- Keep text inputs and controls keyboard accessible. Sheets have a visible close control and an Escape action in the concept.
- Announce saved changes and validation errors without interrupting input. Offer Undo in context and keep applicable history entries accessible.
- Use brief fade-through transitions for main destinations and vertical sheet transitions for contextual actions. Avoid animation that delays the next action.
- Keep readiness, syncing, AI failure and permission states explicit. Avoid numeric readiness until sleep is complete, and distinguish provisional from final readiness in production.

## Implementation approach

Start with shared Compose tokens and components: screen header, contextual coach dock, four-destination navigation, task row, readiness summary, task/progress sheet, proposal card, settings row and feedback snackbar. Keep existing repositories, scheduler checks, approval logic and local storage as the source of truth.

Then replace the navigation shell, Today, task editor/progress and contextual coach together, followed by Plan, Reflect, settings and routine surfaces. This order makes a complete daily loop reviewable early. Route task editing and progress through one shared implementation from every entry point.

Before shipping, verify: partial progress schedules correctly; locked blocks survive replans; prayer and sleep conflicts reject invalid moves; larger coach proposals await approval; Undo restores the right revision; tomorrow selections affect the actual plan; recurring generation does not duplicate tasks; restore validates the file; all app features remain usable when AI is paused. Check layouts with large fonts, TalkBack and both appearances on an Android device.

## Implementation validation

The debug APK builds with JDK 21. All 447 core tests pass, including new tests for actual-day task selection, untimed/catch-up separation and overlap lanes. Android lint completes with no errors; existing dependency and platform warnings remain. Emulator checks covered populated Today and Plan, multiple all-day events, an untimed task, overlapping commitments, week view, the chat sheet, task editor, progress, settings and Reflect, plus dark mode and 320dp width with 1.3x text. Live Google/Health Connect account flows were not exercised in the emulator. Test fixtures were confined to a disposable emulator and are not shipped in the app.
