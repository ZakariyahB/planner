# Stream A: sleep engine

Branch `redesign/a-sleep`. Fixes: broken-up sleep. Source of truth for your sleep is Google Fitbit through Health Connect.

## Goal
Model sleep as episodes inside a wake-anchored sleep day, so a Fajr split, a late second sleep, fragments and naps are all counted, and readiness stops depending on "one night".

## Owns
`android/core/scheduler/.../readiness/*`, `android/app/.../sleep/*`, `android/app/.../ui/sleep/*`, sleep fixtures and tests.

## Provides (see contracts.md)
`SleepApi`, `ReadinessApi`, `SleepDay`, `SleepEpisode`, and Compose pieces in `ui/sleep/` for Stream D to place: `SleepTimeline`, `SleepDayCard`, `EpisodeEditor`.

## Work
1. Replace `SleepGrouping` with episode building: keep every record of any length (no 20-minute filter, no 11:00 to 19:00 nap rule); merge records separated by a short gap; keep awake stretches and gaps as interruptions.
2. Sleep day: a 24-hour window anchored at the main wake-up (the longest episode's end); total, core (longest episode) and extra (the rest, naps included).
3. Source rule: read `dataOrigin` from Health Connect and use Fitbit records only; use another writer only for a day Fitbit has none. Never sum overlapping records.
4. Readiness: inputs become rolling 24 h and 72 h sleep against a configurable need, timing, debt and yesterday's completion. "Final" means the next episode is unlikely (awake for a set number of hours or past the usual wake window), not a clock time. Keep the "waiting for data" state.
5. Edit tools: add, delete, merge and split an episode by hand; manual edits survive the next Health Connect sync.
6. Reflect sleep timeline composable: episodes as bars, interruptions marked, the last 7 days.
7. Migrate existing `sleep_sessions` rows into `sleep_episodes` (table created by Stream 0); keep the old table until the release after.

## Acceptance
- Fixtures from real exported nights (single block, Fajr split, second sleep starting after 11:00, three fragments, Fitbit plus another writer overlapping) all give the expected totals, and the last 14 real nights are within 10 minutes of Fitbit's own totals.
- A nap and a late second sleep are counted; two writers are never double counted.
- A hand edit survives a re-sync.
- Readiness for a Fajr-split day is not computed from the first block alone.

## Agent prompt
Read `docs/REDESIGN.md` sections 1.3 and 3.2, `docs/redesign/README.md` and `contracts.md`. Implement Stream A as listed above on branch `redesign/a-sleep`. Develop against the contracts and fakes; do not touch files outside your paths. Rules (full text in README.md): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.
