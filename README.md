# Planner

Personal Android day planner with an AI coach. Build plan: [docs/PLAN.md](docs/PLAN.md).

## Commands

| What | Command |
|---|---|
| Worker tests | `cd worker && npm ci && npm run typecheck && npm test` |
| Worker local dev | `cd worker && npx wrangler dev` |
| Scheduler tests (no Android SDK needed) | `cd android && ./gradlew :core:scheduler:test -PcoreOnly` |
| App build + all tests | `cd android && ./gradlew :core:scheduler:test :app:assembleDebug :app:testDebugUnitTest` |

Gradle needs JDK 17–21 (Android Studio's bundled JDK is fine). Kotlin 2.0 does not run on JDK 25.

## Local config

`android/local.properties` (git-ignored; Android Studio adds `sdk.dir`):

```
GOOGLE_WEB_CLIENT_ID=<web client id>
WORKER_URL=https://planner-proxy.<account>.workers.dev
```

Worker values live in `worker/wrangler.toml`; the secret is set with `wrangler secret put ANTHROPIC_API_KEY`.

## Google Calendar prerequisites (M1)

In Google Cloud: enable the Calendar API; set up the OAuth consent screen (Testing, you as a test user, scopes `calendar.readonly` and `calendar.app.created`); create an **Android** OAuth client with package `app.planner` and your debug keystore's SHA-1 (`./gradlew signingReport`). The app uses the Authorization API, so no web client ID is needed until M4.

## Status

See the milestone list in docs/PLAN.md. M0 (scaffold) and M1 (data in) are written; `:app` has not been compiled yet. M2 is next.
