# Stream F: calendar sources, lecture rules, write-back

Branch `redesign/f-calendar`. Fixes: calendar events entering the app as a different kind of thing; lectures treated as fixed.

## Goal
Every calendar entry arrives as an item by per-calendar rules, with the right timing, and the plan is written back to Google.

## Owns
`core/.../calendar/*`, `app/.../calendar/*`, `core/.../social/*`, `app/.../social/*`.

## Provides
An importer that outputs `Item` values keyed by `ItemSource.Calendar`, calendar rules, lecture watch items, write-back, and the social usage reader.

## Work
1. **Rules per calendar:** *Busy only* (blocks time, no item), *Item* (becomes an item), *Ignore*. Defaults replace the current selection list.
2. **Mapping:** timed event to `Fixed`, movable false; all-day event to `Window(day)` with duration and time read from title brackets, an hour by default; the title-hint parser stays (`AllDayHintParser`) as one source of duration and time.
3. **Lectures:** every slot is online and watched, so the Lectures calendar maps to "watch" items: `availableFrom` = slot end plus the recording delay (default one hour, a setting), duration = slot length, due by the end of the week. Never fixed.
4. Idempotent re-import: update by key, never duplicate; items you edited keep your edits; removed events drop open items only.
5. Write-back: placed items go to the Planner calendar, edits made there are read back; two-way, the app wins conflicts.
6. Social usage: keep reading Android's own usage events (as Digital Wellbeing does); keep per-screen pairing and screen-off handling.
7. Calendar refresh in the background so imports exist before the 23:30 and morning runs.

## Acceptance
- A fixture week (lectures, tutorials, all-day items with bracket hints, a multi-day event, a deleted event) imports to the expected items and survives re-import unchanged.
- Lecture watch items are never placed before the slot end plus delay.
- Editing an imported item keeps the edit after the next import.
- Existing calendar and social tests pass, ported to items where needed.

## Agent prompt
Read `docs/REDESIGN.md` sections 1.1 and 3.1, `docs/redesign/README.md` and `contracts.md`. Implement Stream F on branch `redesign/f-calendar`. Output `Item` values only; do not edit the scheduler. Rules (full text in README.md): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.
