# Stream A: engine — notes

Pure Kotlin in `android/engine`, no Android, no I/O, no clock. Run: `android/scripts/jvm-test.sh engine`.

## What is there

| File | What |
|---|---|
| `engine/schedule/DefaultBlockPlacer.kt` | `placeBlock` (newplan 5.2) |
| `engine/prayer/DefaultPrayerModel.kt` | anchors, offsets, weekend interpolation, absorption (5.1) |
| `engine/schedule/TierWindows.kt` | a day's work window and tiers (5.3); public, so B can word descriptions ("Uses rest zone") from it |
| `engine/schedule/DayPlanner.kt` | internal: placing one day's tasks (5.4, 5.5) |
| `engine/schedule/DefaultPlanningEngine.kt` | `plan` and `rollover` (5.5 to 5.7), plus `object Engine { placer, prayers, planning }` |
| `engine/schedule/WeekTags.kt` | finds and strips `w-1` style tags |

Wiring for other streams: `Engine.planning` (or `DefaultPlanningEngine()`), `Engine.prayers.resolve(...)` before building a `PlanRequest`.

## How it behaves (and where it goes beyond or differs from the plan)

**placeBlock.** Exactly 5.2. A start inside a slot moves to the slot end (repeated if that lands in another slot). The buffer loop runs to a fixed point; an anchor at `end` does not count. `Collides` names the earliest overlapping fixed event. Fajr anchors are ignored even if passed in.

**Prayer model.**
- Offsets are added to imported and interpolated times alike.
- Interpolation: known days on both sides within 7 days give linear interpolation between the nearest one each side; known days on one side only (the normal weekend case before next week is imported) give the nearest value moved by the least-squares slope over up to 5 nearest known days on that side. Rounded half up to the minute. No known day within 7 days: no anchor, and the plan carries a `Note` ("No prayer times for ...; planned without prayer buffers").
- The arithmetic runs in UTC minutes, so the weekend of a BST/GMT change (always a weekend in the UK) comes out right. Tested for both changes in 2026.
- A missing **weekday** is interpolated too (marked `interpolated`), not only Saturday and Sunday. Better than planning that day without prayer slots.
- An imported Friday Dhuhr is dropped. Duplicate imports: the first wins.
- Absorption: an anchor in `[event.start, event.end)` is dropped, including for `assumed` events (the Halaqa). `afterPrayer` still counts from an absorbed prayer's own slot, and the tier boundary still uses an absorbed Maghrib.

**Tiers.** Tier 0 is wake to the end of the Maghrib slot (the slot itself is tier 0: a block may run through Maghrib with its buffer without "using rest time"). Rest is slot end to `normalEnd`, empty when Maghrib is later (summer). Windows are half open, so a block ending at 20:00 is tier 2. A placement's tier is the highest it touches. Day flags `USES_REST_TIME` / `EXTENDED_DAY` / `OVER_SOFT_LIMIT` are set when some block actually overlaps that window (so Tuesday, 18:00-19:15 only, is `EXTENDED_DAY` but not `USES_REST_TIME`; `FakePlannerQueries` sets them cumulatively). Boundaries are forced into order, so odd settings cannot make negative windows.

**Order (5.5).**
1. Constrained tasks (`earliestStart` or `afterPrayer`), earliest constraint first (input order would put Tuesday's Arabic hw before its lecture), each at its earliest feasible time.
2. The rest by rank: priority (higher first), then deep before passive; the `OVERFLOW` kind (the catch-up block) always last.
3. Tasks of equal rank (up to 5, so at most 120 orders) are tried in every order and the best kept: least overflow, then fewest minutes over the soft limit, in extended hours, in the rest zone, then fewest chunks, then earliest finish; the user's order wins ties and the search stops at the first order with nothing outside tier 0 and no splits. This is what packs Wednesday (below). Larger equal groups keep the user's order.

**Tiers and splitting.** Each task tries tier 0, then 1, 2, 3. Within a tier it goes into the first gap where it fits as one block. It splits only if it fits as one block nowhere within that tier, and only in a gap that ends at something busy (a fixed event or another block), never at a tier boundary or the hard cap: a split there would just give two chunks back to back. Chunks are the largest piece that fits the gap leaving a remainder of 0 or at least the minimum; each chunk runs the prayer rule itself. Minimum chunk is `max(task.minChunkMin, settings.minChunkMin)` (the task field defaults to 45, so a lower setting cannot shrink it below the task's). Chunks are numbered in time order.

**Overflow.** What does not fit is an `Overflow` proposed for the catch-up date (`catchUpDate`, else `Settings.catchUpDay` of the first day's week), never the next day. Overflow from the catch-up day itself or later has `proposedDate = null` (the user decides). A split task may be partly placed; only the rest overflows. Reasons: try the task's whole remaining work alone on the day as it was before planning; fits: `NO_ROOM`; fits only without its constraint (or its `afterPrayer` has no anchor, e.g. Friday Dhuhr): `CONSTRAINT_UNSATISFIABLE`; otherwise, or the day is already over by `notBefore`: `PAST_HARD_CAP`.

**Replanning (5.6).** `keep` placements stay exactly where they are, are planned around, and their work counts towards their task; only the rest is placed. They are returned in `placements` along with new ones, and each task's chunks are renumbered in time order (a kept `(2/2)` whose first chunk was dropped becomes a single block; B should expect a title edit there). Their tier is recomputed; times, buffer and covered prayers are as given. `notBefore`: nothing new starts before it; earlier days get nothing (`PAST_HARD_CAP`).

**Other request shapes.** Tasks with `assignedDay` outside `days`, and `DONE`/`SKIPPED` tasks, are ignored. A task with no `assignedDay` goes, after the day-assigned tasks, on the requested day where it fits in the lowest tier (earliest day on a tie, days up to its deadline first). `DeadlineMissed` when a block ends after the deadline or overflow has no proposed date or one after it. `AssumedGapKept` for every `assumed` fixed event in range.

**Rollover (5.7).** Tasks are shifted by the difference between the two week starts (7 days), with deterministic new ids (name-based UUIDs of the old id and target week), `BACKLOG`, no event links and no `sourceEvent` (the target week has no all-day template events yet). Deadlines shift too: the template is relative to its week. Titles keep week tags unless `stripWeekTagsOnRollover` (only short tags like `w-1`, `W3`, `(w-2)`; "Week 3 problem sheet" is content). `expectedRecurring` may be dated in either week; each is mapped to the same weekday of the target week and counts as present if the target week has a real event with the same title (trimmed, any case) that day. Missing ones are planned around as assumed events, reported as `AssumedGapKept` and in `notes`.

**Clock changes within a day.** The engine works in wall-clock `LocalDateTime`. The changes happen at 01:00-02:00, outside any sane work window (wake 09:30 to hard cap 22:00), so block lengths are right. Only interpolation crosses days, and that is done in UTC.

## 13.3 reference week: differences from the reference and why

All invariants hold, nothing overflows, every task is on its own day, PintOS is 8h, and the days over 18:00 are the same (Mon, Tue, Wed, Thu, Sun). Monday, Tuesday, Thursday and Friday match the reference block for block. The engine's full output is pinned in `ReferenceWeekPlanTest`. The fixture's task constraints are right; no change requested.

| Day | Reference | Engine | Why |
|---|---|---|---|
| Wed | P&S CW 09:30-10:15 (1/2), 16:46-17:31 (2/2); OS worksheet 12:45-14:00 (1/2), 17:31-18:31 (2/2) | OS worksheet 09:30-10:15 (1/2), 12:45-14:00 (2/2); P&S CW 16:30-18:30 (Maghrib + Isha) | The equal-rank search puts the worksheet into both morning gaps, which leaves the CW whole. Same finish (18:30 vs 18:31), one chunk fewer. The CW starts at 16:30, the Halaqa end, which is legal (16:30 is not inside the Maghrib slot 16:31-16:46) and covers Maghrib, so its buffer is 30. The reference waited for the slot to end. |
| Sat | Tutorial P&S 09:30-10:30, Lecture SDE 10:30-11:30, P&S CW 11:30-13:45 | P&S CW 09:30-11:30, Tutorial P&S 11:30-12:45, Lecture SDE 12:45-13:45 | 5.5 "deep work earlier, passive (lectures) later". Same finish, same total buffer. |
| Sat | Arabic hw 14:15-16:15 | 14:14-16:14 | "After Asr": Asr is 13:59, so its slot ends 14:14. The reference rounded. |
| Sun | PintOS 09:30-11:30 (1/2), 13:15-14:30 (2/2) | 09:30-11:45 (1/2), 13:15-14:15 (2/2) | The first chunk is the largest that fits before the Arabic lesson while leaving at least 45 min (135 + 45). Same total. |
| Sun | Internship apps 14:30-16:45 (rest zone) | 14:15-16:15 | Follows from the shorter second PintOS chunk; Internship now ends before Maghrib and stays in tier 0. |
| Sun | Catch-up 16:45-19:00 | 16:15-18:45 (Maghrib + Isha) | Starts right after Internship; ends 15 min earlier. |

Prayer table: resolving the weekday imports with the prayer model gives the table's weekend Asr, Maghrib and Isha exactly; weekend Dhuhr comes out 11:49, not 11:48, because Monday to Thursday are all 11:49 (no Friday Dhuhr), so there is no trend to follow. The plan is identical either way (tested).

## Open points

- **Starting a minute before a prayer** is allowed by 5.2 (Wednesday's CW starts 16:30, Maghrib 16:31). If that feels wrong in use, a setting like "do not start within N min before an anchor" would slot into `DefaultBlockPlacer` (treat `[a - N, a + slot)` as the no-start zone). Not added, since the plan's rule is explicit.
- Equal-rank groups larger than 5 keep the user's order instead of searching.
- No `CHANGES.md` requests from this stream.

## What to test by hand

Nothing in the UI belongs to this stream. Once B wires the engine: plan the real week and compare with what you would have done; check a Friday (no Dhuhr buffer, Jumu'ah prep respected), a weekend ("interpolated" flagged), a day with the Halaqa (no Asr buffer, nothing inside it), and a replan after "ran over by 1h" (kept blocks unmoved, nothing before now, overflow to Sunday rather than tomorrow).
