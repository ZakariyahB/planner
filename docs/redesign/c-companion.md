# Stream C: AI companion brain

Branch `redesign/c-companion`. Fixes: the AI making little impact. The AI becomes the product: it plans, acts, reports.

## Goal
A planner agent that builds the day and week, repairs it when things change, applies changes with Undo, and learns how long things take you.

## Owns
`core/.../ai/*`, `app/.../ai/*`, `docs/prompts/*`, `worker/*`, the evaluation harness.

## Provides
`CompanionRunner`, `AutonomyPolicy`, `CompanionResult`, the use case producing the briefing, and the evaluation harness.

## Work
1. Modes: PLAN (build or rebuild a day or week as one diff), REPAIR (small diff after a trigger), COACH (sleep and workload), CHAT. Each has its own prompt, round limit and budget.
2. Tools over `PlanApi`: place, move, split, set estimate, defer, drop, set preference, plus the read tools (day, items, sleep, readiness, social). The scheduler validates; the model fixes violations it is told about.
3. Trust ladder: `AutonomyPolicy` per change type. Defaults: apply with Undo for everything except drop, which asks. One transaction and one undo token per action.
4. Briefing: morning and evening content (what changed, why, what is next) in a few lines, built from real numbers.
5. Profile and learning: record plan versus actual per item and tag; keep a compact structured profile (actual versus estimated minutes, focus hours, routinely skipped items); send it as a cached context block. Correct estimates from history before the model sees them.
6. Worker: model routing (stronger for PLAN, cheaper for REPAIR and scheduled runs), prompt caching, the monthly cap.
7. **Evaluation harness** in core: replayable scenarios (a week with three deadlines and a bad night; Halaqa overruns; an empty day with five all-day items; five lecture watch items), a recorded-run mode and a stub-model mode, scoring valid plan, deadlines met, load within the cap, number of taps needed. Runs in CI.

## Acceptance
- Harness passes in stub mode in CI; recorded runs are stored for regression.
- PLAN produces a valid week in one run; REPAIR after an overrun moves the minimum needed.
- Applying and undoing an AI change leaves no residue.
- Prompts and tool schemas stay consistent (extend `PromptFilesTest`).
- Cost per day on typical use stays inside the monthly cap.

## Agent prompt
Read `docs/REDESIGN.md` sections 1.2 and 3.3, `docs/redesign/README.md` and `contracts.md`. Implement Stream C on branch `redesign/c-companion`, against `FakePlanApi` and `FakeSleepApi`. Build the evaluation harness first and make each prompt change measurable. Rules (full text in README.md): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.
