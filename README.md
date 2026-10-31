# Planner

Personal Android day planner. It plans tasks into time around your prayer times and calendar, writes them to Google Calendar only after you approve, checks in at the start and end of each block, and has an in-app Claude that turns plain requests into proposals.

- Install: [docs/SETUP.md](docs/SETUP.md)
- Use: [docs/MANUAL.md](docs/MANUAL.md)
- v2 spec: [docs/redesign/newplan.md](docs/redesign/newplan.md); how it was built: [docs/redesign/STREAMS.md](docs/redesign/STREAMS.md), with the reconciliation in [docs/REDESIGN.md](docs/REDESIGN.md) section 7 and per-area notes in `docs/redesign/notes/`
- UI mocks (chosen direction: Rhythm): [docs/redesign/mocks/](docs/redesign/mocks/)

## Architecture

```
:app (Compose UI, Room, Google sign-in, alarms, Worker client)
  ├─ :calendar   Google Calendar gateway, sync, PlannerService, ApprovalGate, the only CalendarWriter
  ├─ :assistant  Claude tool loop: read tools, find_events, propose_changes, replan. No apply tool
  ├─ :engine     domain model, scheduling engine, prayer model, ChangeSet model, ports, parsers, check-ins, codecs
  └─ :core:scheduler  sleep and readiness, social time, legacy logic
worker/          Cloudflare Worker: holds the Anthropic key, checks the Google ID token, enforces the monthly cap
```

The hard rule: only `CalendarWriter.apply(ApprovedChangeSet)` writes to Google Calendar, and only `ApprovalGate` (called by the approval screen) can make an `ApprovedChangeSet`. `:assistant` does not depend on `:calendar`, and a test fails if it ever does.

## Commands

| What | Command |
|---|---|
| Pure-Kotlin tests, no Android SDK needed | `android/scripts/jvm-test.sh` (or name modules: `core engine calendar assistant`) |
| App build and all tests | `cd android && ./gradlew :core:scheduler:test :engine:test :calendar:test :assistant:test :app:assembleDebug :app:testDebugUnitTest` |
| On-device database tests | `cd android && ./gradlew :app:connectedDebugAndroidTest` |
| Worker tests | `cd worker && npm ci && npm run typecheck && npm test` |
| Deploy the Worker | `cd worker && npx wrangler deploy` |
| Install the release build on a connected phone | `cd android && ./gradlew :app:installRelease` |

Gradle needs JDK 17 to 21 (Android Studio's bundled JDK is fine).

## Local config

`android/local.properties` (git-ignored; Android Studio adds `sdk.dir`):

```
GOOGLE_WEB_CLIENT_ID=<web client id>
WORKER_URL=https://planner-proxy.<account>.workers.dev
```

Worker values live in `worker/wrangler.toml`; the secret is set with `wrangler secret put ANTHROPIC_API_KEY`.
