# Stream D: UI (notes for the final docs)

Visual direction **C · Rhythm** (`docs/redesign/mocks/C-*.dc.html`), plus A's multi-change approval list and recurring
scope picker, and B's STALE/VERIFIED apply report with Undo and Backlog "Needs a look".

## What is where

| Path | What |
|---|---|
| `ui/theme/RhythmTokens.kt` | Colour tokens (light and dark) as plain numbers, the WCAG contrast function and the list of text pairs |
| `ui/theme/RhythmPalette.kt`, `RhythmType.kt`, `Theme.kt` | Compose colours, Material mapping, Fraunces + DM Sans (Google Fonts provider), `PlannerTheme` |
| `res/values/font_certs.xml` | Google Play services font provider certificates (both verified with `openssl verify`) |
| `ui/model/*` | Pure screen logic (no Android): `DayTimelines`, `WeekRhythm`, `ApprovalCards`, `BacklogItems`, `ExecutionLogs`, `Outcomes`, `LaunchRequest`, `Fmt` |
| `ui/rhythm/*` | Shared components: pill buttons, cards, header, banner, flag chip, floating nav |
| `ui/today`, `ui/week`, `ui/backlog`, `ui/ask`, `ui/approval`, `ui/settings` | One view model and one screen file each (plus the task editor sheet) |
| `ui/shell/UiLaunch.kt` | Reads the notification extras `open` / `changeSetId` |
| `ui/PlannerRoot.kt` | The new shell (replaces the old Today/Plan/Tasks/Reflect tabs) |
| `ui/preview/Previews.kt` | Previews from the reference-week fakes, light and dark |

Tests: `android/app/src/test/kotlin/app/planner/ui/**` (34 tests, JUnit 4) run in CI with `:app:testDebugUnitTest`.
They only use pure code, so they also run on a plain JVM build that includes `engine`, `calendar`, `app/.../ui/model`
and `ui/theme/RhythmTokens.kt`.

## Theme

- Light: ground `#F4EFE6`, cards `#FFFDF8`, ink `#1F1B16`, muted `#6B5F50`, accent `#0F4C4A`, amber text `#8A4B17`,
  amber line `#B06A2E`. Dark: ground `#14120F`, surface `#1E1B17`, text `#F4EFE6`, muted `#B8AD9C`, accent `#7CC4BE`,
  amber `#E2B88F`. `RhythmTokensTest` checks every text pair the screens use is at least 4.5:1 in both.
- Follows the phone's light/dark setting, unless Settings → Appearance says Light or Dark (the existing
  `ThemeRepository`; MainActivity reads it before the first frame).
- Fonts download through Google Play services. Each family lists the phone's serif / sans-serif after the downloaded
  font, so text draws immediately and stays readable if the download fails; failures are logged, never thrown
  (`createFontFamilyResolver` with an exception handler).
- The older screens (Reflect, Sleep, Social, Backup) use the same Material colours, so they match.

## Behaviour (user-facing)

- **Navigation:** floating dark pill at the bottom: Today, Week, Backlog, Ask. Ask shows a badge with the number of
  changes waiting for approval. Settings is the gear on Today; Reflect is reachable from Settings and from the bottom of
  Week. Back steps out of Settings / Reflect, then back to Today.
- **Today:** heading is the stretch you are in ("Before Dhuhr"), with "1h 09m to Dhuhr at 11:49 · PintOS carries on
  after". The day is drawn as stretches between the prayers (Dhuhr, Asr, Maghrib with "rest until 18:00", Isha); a block
  sits in the stretch holding most of it (Lecture - SDE at 14:00 with Asr at 14:04 shows after Asr, as on the mock).
  An empty stretch says "PintOS until 12:45, then free till 14:00". Extended blocks are amber and their stretch line is
  dashed amber. The block happening now is the large teal card with **Done** and **Need 30 more**, plus Log start and
  More. Tapping any block opens Start / Done / Need 30 more / Stop here / Skip / Open in Google Calendar. Done asks
  "How focused were you?" (1 to 5) when that preference is on. Logging writes to `ExecutionLogStore` only (no
  approval). Need 30 more / Stop here log the outcome and call `ProposalPort.replan`; the approval opens with the
  result. Banners: check-in setup (`CheckInSetupCard`, hidden once everything is allowed), "Sign in again" when
  Google expired, "N changes waiting" with Review. Unplanned template items for today get **Plan today**. Footer:
  Open in Google Calendar, last sync and Sync now.
- **Week:** "The week's rhythm": per day, five bars (to Dhuhr, to Asr, to Maghrib, to Isha, after Isha) filled by
  planned time (teal work, amber extended, grey fixed), the Dhuhr time ("~" for estimated weekend times, "Jumu'ah" on
  Friday) and the end time (amber after the normal end). Tap a day for its blocks and flags. Arrows change week. The
  card at the bottom previews **next week from the template** (`ProposalPort.rollover`), which opens the approval.
- **Backlog:** "N to plan · M need a look". Needs a look (amber) shows the title as written and why it did not parse;
  Fix opens the editor prefilled (title without the number, calendar, day) and removes it from the list on save.
  Tasks show duration, calendar and chips (day, from HH:MM, after Asr, due, one block, priority). Tap to edit; Plan
  proposes `ScheduleTask` and opens the approval. Import from calendar re-reads this week's and next week's all-day
  template events. Saving a task is local (no approval).
- **Ask:** the assistant conversation (`AssistantPort`), quick prompts (Halaqa ran over, Plan tomorrow, Copy
  template), and under each reply that proposed something a card with its summary and **Review**.
- **Approval:** one change opens as a bottom sheet ("Not now" / "Approve and update Google" / "Reject this change");
  several open as a full-screen list with a checkbox per change, "Reject all" and "Approve N of M". Each card shows
  the kind and calendar ("MOVE · ACADEMIC"), title (and the old title on a rename), date (both dates when the day
  changes), before and after times as bars over the day window, the engine's note ("Includes 15 min for Isha"), flags
  (amber for extended, over the limit, external organiser, missed deadline) and the exact target event. Repeating
  events get a scope picker (this one only / whole series; "this and following" is shown disabled because the writer
  does not support it yet). After applying: "All 2 changes are in Google Calendar" or "Applied 2 of 3" with a line per
  change (VERIFIED, STALE with the writer's reason, FAILED), Undo on the report and a snackbar with Undo
  (`CalendarWriter.undo`). The approval view model is the only caller of `ApprovalGate`.
- **Notifications:** `open=approvals` (with optional `changeSetId`) opens that proposal (or the newest pending one);
  `open=today|week|chat|settings` switch tab or open Settings. The extras are removed after reading, so rotation does
  not reopen anything. The legacy `RoutineLaunch` extra still works.
- **Settings:** Google sign-in status, Sign in / Sign in again (`GcalAuthorizer.authorize`, consent screen only on
  tap) and Sync now; check-in setup; work day times (start, normal end, extended end, hard cap); prayer slot length and
  per-prayer offsets; smallest chunk; recording delay; catch-up day; writable calendars; convert all-day items; strip
  week tags; check-in preferences; prompt times (night-before review, Sunday deadlines, Fajr prompt day and time);
  sleep need; assistant model; appearance; links to Reflect, Sleep and Health Connect, Social time, morning brief and
  lights-out, Backup and restore.

## What to test by hand

1. Light and dark: switch the phone's dark mode with Appearance on "Match my phone"; then force Light and Dark.
   Check Today, Week, Backlog, Ask, Settings, the approval sheet and Reflect in both.
2. Fonts: on a phone with Google Play services, headings turn Fraunces within a second of first launch. In airplane
   mode on a fresh install, the app still opens with the phone's serif and sans-serif.
3. Font scaling at 200%: Today's now card buttons wrap, nothing is clipped, the nav labels still fit or ellipsize.
4. TalkBack: nav tabs read "Ask, 2 waiting for approval"; prayer markers read "Dhuhr at 11:49"; week rows read
   "Monday, ends 19:15, extended day, 4 blocks"; approval cards read the whole diff.
5. Today with the reference week: on a Tuesday morning PintOS is the now card; tap Need 30 more and the approval sheet
   appears; Approve, then Undo from the snackbar.
6. From a check-in notification "Review change": the approval for that change set opens, also when the app was closed.
7. Week → Preview next week: the full-screen list opens with a checkbox per change; untick one and approve.
8. Backlog → Import from calendar, then Fix a Needs a look item; it leaves the list.
9. Google sign-in expired: Today shows "Sign in again"; tapping it shows Google's consent screen.

## Differences from the plan and why

- The Today blocks are grouped by midpoint, not start, so a block that starts a few minutes before a prayer reads
  under the prayer it mostly follows (as the mock shows).
- The now card shows Done and Need 30 more (the mock); Start is a smaller "Log start" link on it and a full button in
  the block sheet.
- "This and following" is visible but disabled until the calendar writer supports it.
- Settings has a model text field rather than a key field: the assistant goes through the Worker (Stream C), so there
  is no Claude key to enter.
- Chat has no streaming (the assistant port returns whole replies).
- The old Plan (calendar grid), Tasks and coach-sheet screens are no longer reachable; their files stay until the
  master removes dead legacy code. Reflect and the older Settings pages are reachable from Settings.
