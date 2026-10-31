# Stream G: migration, backup, CI, docs

Branch `redesign/g-data`. Fixes: risk. Nothing is lost, nothing merges unchecked.

## Goal
Existing data survives the redesign, backups round-trip, and CI checks every stream.

## Owns
`app/.../backup/*`, `app/.../data/Migrations*`, `.github/*`, diagnostics, `docs/redesign/notes/*` collection.

## Provides
The data migration runner, backup format v2, CI.

## Work
1. **CI first** (merge before the others): run core tests with Gradle on every PR into `redesign/*`; keep `android-ci` (assemble and unit tests); add a job that runs the evaluation harness (when Stream C lands) and a job that fails if a stream edits files it does not own (path rules from README.md).
2. **Migration runner:** one-off migration from the old tables (tasks, blocks, sleep sessions, commitments, all-day ticks) to the new ones, run once after the schema upgrade, idempotent, with a pre-migration backup written automatically. Uses mapping functions from Streams A and B.
3. **Backup format v2:** items, placements, episodes, profile, event log opt-in; still restores v1 files; unknown tables ignored; refuses newer versions.
4. Restore test: a real pre-redesign backup file restores and migrates with nothing lost.
5. Diagnostics screen data (what the event log shows), crash capture hook for Stream E.
6. Collect the per-stream notes in `docs/redesign/notes/` for the master agent.

## Acceptance
- A v1 backup file from today's app restores, migrates, and every task, block and sleep row maps to an item or episode.
- Running the migration twice changes nothing.
- CI fails for a PR that edits another stream's files or breaks core tests, and passes for a clean one.
- Backup v2 round-trips.

## Agent prompt
Read `docs/redesign/README.md` and `contracts.md`. Implement Stream G on branch `redesign/g-data`; land the CI changes first on their own small PR. Rules (full text in README.md): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.
