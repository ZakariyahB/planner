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

## Sleep data (M3)

The app reads sleep from Health Connect. In the Fitbit app, turn on sharing to Health Connect (sleep), then tap Allow in the app. Until data arrives, readiness shows "waiting" and sleep can be entered by hand on the Today card.

## Assistant (M4)

The assistant calls Claude through the Cloudflare Worker, which holds the API key, picks the model (sonnet for chat and replans, haiku for scheduled runs) and refuses calls once the monthly cap is reached.

Deploy the Worker once:

```bash
cd worker
npm ci
npx wrangler kv namespace create USAGE      # paste the id into wrangler.toml
npx wrangler secret put ANTHROPIC_API_KEY
npx wrangler deploy                          # prints the planner-proxy URL
```

Set `GOOGLE_WEB_CLIENT_ID` in `worker/wrangler.toml` to the **Web** OAuth client ID, and put the same value and the Worker URL in `android/local.properties` (`GOOGLE_WEB_CLIENT_ID`, `WORKER_URL`). Rebuild, then Settings → Assistant → Sign in, and pick the allowed account.

`worker/src/pricing.ts` holds estimated per-token prices used only to enforce the cap. Check them against Anthropic's pricing page and edit if they have drifted. Only token counts and a monthly total are stored, never message content.

The system prompt and tool definitions live in `docs/prompts` and are bundled into the app as-is.

## Status

See the milestone list in docs/PLAN.md. M0 to M4 are written. The pure-Kotlin core and the Worker are tested; the `:app` module was written without an Android SDK to compile against, so check it in Android Studio. M5 (routines), M6 (social time) and M7 (polish) are not started.
