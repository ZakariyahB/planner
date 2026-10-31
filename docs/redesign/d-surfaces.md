# Stream D: screens, command bar, voice, widget

Branch `redesign/d-surfaces`. Fixes: too much to operate; makes the app feel like a companion.

## Goal
Three surfaces, an always-present command bar, voice, and a widget. You check in; you do not manage.

## Owns
`app/.../ui/*` (except `ui/sleep/`, owned by Stream A), resources, navigation, the widget.

## Provides
The finished UI, built on the state types in `contracts.md`.

## Work
1. **Companion (home):** the briefing, changes the companion made with Undo, the current item with start and duration, the rest of today, one-line readiness. No setup cards once set up.
2. **Day / Week:** one timeline of items, fixed events, sleep episodes (shaded) and free time. Drag to move, tap an empty slot to add or ask.
3. **Reflect:** sleep timeline (Stream A's composables), planned versus done, social time. Read-only.
4. **Command bar** on every surface: type or speak; short replies stating what changed; suggestion chips.
5. **Voice:** speech recognition first, typing second.
6. Settings cut to: Calendars (with the rules from Stream F), Sleep, AI autonomy (per change type), Notifications, Backup.
7. Widget and lock-screen "current item".
8. First-run onboarding under three minutes.
9. One list component for items: origin is a badge only.

## Acceptance
- App opens on the briefing; every item type renders and acts the same.
- Builds and runs against `Fake*` implementations with no other stream merged.
- No screen has an `if (source == …)` outside the badge.
- First run to a first plan in under three minutes on a fresh install.
- Screens pass a basic accessibility check (touch targets, contrast, content descriptions).

## Agent prompt
Read `docs/REDESIGN.md` sections 1.4 and 3.4, `docs/redesign/README.md` and `contracts.md`. Implement Stream D on branch `redesign/d-surfaces` against the `Fake*` implementations; wire real implementations only by their interfaces. Rules (full text in README.md): branch from `redesign/contracts`, open your PR into `redesign/integration`. Set `git config user.name "Zakariyah Bello"` and `git config user.email "bello.zakariyah20@gmail.com"` before committing. Do not put any AI credit (no Co-Authored-By, Claude-Session, "Generated with") in commits, PR titles or PR bodies. Edit only the paths you own. Ask for contract changes in `docs/redesign/CHANGES.md`. Pure logic goes in `core/scheduler` with tests; the app module is compiled by CI, so keep the PR green on `android-ci`.
