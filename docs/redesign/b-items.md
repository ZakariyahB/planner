# Stream B: unified items and scheduler

Branch `redesign/b-items`. Fixes: calendar items and tasks being different things.

## Goal
One `Item` model for everything (typed task, template task, timed event, all-day event, lecture slot, commitment), one scheduler over it, one plan API.

## Owns
`core/.../model/*`, `core/.../scheduler/*`, `app/.../plan/*`, `app/.../data/Item*`, `Block*`, `Task*` and their DAOs.

## Provides
`PlanApi` and the `Item` implementation behind it; extended `ChangeSet` ops (`Place`, `Move`, `Split`, `SetEstimate`, `Defer`, `SetStatus`, `Drop`); use cases that produce `DayState` and `CompanionHomeState` pieces.

## Work
1. Scheduler works on items: Fixed items become unmovable busy time; Window items must land on their day inside their hours; Due and Open items are placed by deadline, priority and `availableFrom`. Keep the existing rules (prayer buffers, work window, Friday, sleep, daily cap, catch-up days).
2. A done, partly done, skipped or dropped item is handled the same whatever its source. Fixed items can be marked attended or skipped.
3. `Split`: one item into several placed parts (the old "never split" rule becomes a default, not a law).
4. Replan transaction with one undo token; `ChangeSet` validation returns hard violations and warnings.
5. Replace the tasks/blocks/commitments split with `items` plus placement rows; remove the all-day conversion bolt-on once Stream F feeds items directly.
6. Free windows and conflicts exposed through `PlanApi`.
7. Migrate existing tasks, commitments and calendar-derived tasks (data side owned with Stream G: B writes the mapping functions in core, G runs them).

## Acceptance
- No code outside a badge and re-import keying branches on `ItemSource`.
- Every item can be ticked, edited, moved, skipped and dropped identically.
- Existing scheduler tests pass after being ported to items, plus new tests for Fixed, Window, Split, Skipped.
- Repeated imports never create duplicates (idempotent by `ItemSource.Calendar` key).
- Undo of any applied change set restores the prior plan exactly.

## Agent prompt
Read `docs/REDESIGN.md` sections 1.1 and 3.1, `docs/redesign/README.md` and `contracts.md`. Implement Stream B as listed above on branch `redesign/b-items`. Port the existing scheduler tests before changing behaviour. Develop against fakes for sleep and the companion. Rules (full text in README.md): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.
