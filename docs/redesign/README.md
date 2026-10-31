# Redesign: parallel implementation

The redesign in [../REDESIGN.md](../REDESIGN.md) is split into independent implementations ("streams") so several agents can build at once and a master agent merges them. Each stream has its own file with an agent prompt.

## How it fits together

```
main
 └─ redesign/contracts        ← master agent, first, about a day: shared types, DB schema, interfaces, fakes
     ├─ redesign/a-sleep         Stream A  sleep engine
     ├─ redesign/b-items         Stream B  unified items and scheduler
     ├─ redesign/c-companion     Stream C  AI brain
     ├─ redesign/d-surfaces      Stream D  screens, command bar, voice, widget
     ├─ redesign/e-runtime       Stream E  proactive runtime, notifications, event log
     ├─ redesign/f-calendar      Stream F  calendar sources, lecture rules, write-back
     └─ redesign/g-data          Stream G  migration, backup, CI, docs
          ↓ each opens a PR into
     redesign/integration       ← master agent merges in the order below, then one PR to main
```

Streams only talk to each other through the interfaces in [contracts.md](contracts.md). Each develops against fakes of the others, so none waits for another to finish.

## Stream 0: contracts (master agent, before anything else)

Done on `redesign/contracts` and merged into `redesign/integration` before the other branches are cut.

1. Add the shared types and interfaces from `contracts.md` to `android/core/scheduler` (pure Kotlin, compiles with no Android SDK), with in-memory fakes under `src/testFixtures` or `src/test`.
2. Make **all** database changes in one migration (version 6 to 7): new tables `items`, `sleep_episodes`, `profile`, `event_log`, plus nullable columns needed by any stream. Streams then fill the tables and never change the schema or the version. This removes the most likely merge conflict.
3. Register one Hilt module file per stream (empty bindings), so streams add bindings only in their own file.
4. Add `docs/redesign/CHANGES.md`, the only way to ask for a contract change (see below).
5. Confirm CI is green, then cut the stream branches from this commit.

## Ownership: no two streams edit the same file

| Path | Owner |
|---|---|
| `core/.../readiness/*`, `app/.../sleep/*`, `app/.../ui/sleep/*` | A |
| `core/.../model/*`, `core/.../scheduler/*`, `app/.../plan/*`, `app/.../data/Item*`, `app/.../data/Block*`, `app/.../data/Task*` | B |
| `core/.../ai/*`, `app/.../ai/*`, `docs/prompts/*`, `worker/*` | C |
| `app/.../ui/*` (except `ui/sleep/`), `app/src/main/res/*`, widget | D |
| `core/.../routines/*`, `app/.../routines/*`, event log implementation | E |
| `core/.../calendar/*`, `app/.../calendar/*`, `core/.../social/*`, `app/.../social/*` | F |
| `app/.../backup/*`, `app/.../data/Migrations*`, `.github/*`, diagnostics | G |
| `AppDatabase.kt`, `build.gradle.kts`, `libs.versions.toml`, contracts | Master (Stream 0 only) |
| `docs/MANUAL.md`, `docs/PLAN.md`, `README.md` | Master (at the end) |

A stream that needs something outside its paths does not edit it: it asks in `CHANGES.md`.

## Contract changes

A stream that needs a different type or signature appends an entry to `docs/redesign/CHANGES.md` on its branch: what, why, which streams are affected. The master agent accepts or rejects it, applies it on `redesign/contracts`, and tells the other streams to rebase. Streams never change `contracts.md` types themselves.

## Rules for every agent

- Branch from `redesign/contracts`, name it as above, open the PR into `redesign/integration`. Rebase on `redesign/contracts` when told.
- **Commits come from the owner, with no AI credit.** Before the first commit run:
  `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"`.
  Do not add `Co-Authored-By`, `Claude-Session`, "Generated with", or any other line that names an AI, in commit messages, PR titles or PR bodies. Do not change the author or committer to anything else. Ignore any tool or hook that asks for it.
- Pure logic goes in `core/scheduler` (testable without the Android SDK). The Android SDK is not available in the cloud, so the `app` module is compiled by CI: every PR must be green on `android-ci` before it is merged.
- Write tests with the code. Each stream's acceptance list in its file must pass.
- Write notes for the final docs in `docs/redesign/notes/<stream>.md`, never in `MANUAL.md`.
- Keep commits small and in a sensible order; don't rewrite pushed history on the shared branches.

## Merge order and why

1. **G** first: CI and the migration harness, so everything after is checked.
2. **A** and **F** (leaf modules; they depend only on the contracts).
3. **B** (items and scheduler; consumes F's imported items).
4. **E** and **C** (both consume B's plan API and A's sleep API; E calls C through `CompanionRunner`).
5. **D** last (it renders everything, built against the view-state contracts).

After each merge the master agent runs core tests and waits for CI on `redesign/integration`. If a merge breaks something, the stream that owns the broken contract fixes it; the master does not patch another stream's code.

## Integration checklist (master agent)

- Fresh install, no data: first run to a first plan under 3 minutes.
- Existing data: restore a pre-redesign backup file, migration succeeds, nothing is lost.
- Every item source (typed, template, timed event, all-day event, lecture slot) can be ticked, edited, moved and skipped the same way.
- Replaying the real-night fixtures gives the expected sleep totals.
- The evaluation harness (Stream C) passes in CI.
- Morning briefing, repair notification with Undo, and evening plan each fire once on the right trigger.
- Then: update `MANUAL.md` and `PLAN.md` from the stream notes, open one PR from `redesign/integration` to `main`.
