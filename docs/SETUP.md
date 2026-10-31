# Setting up Planner

Planner is a personal app that you build and install yourself. This guide takes you from nothing to a working install, in the order that avoids going back. Allow about an hour, most of it waiting on Google Cloud and Cloudflare screens.

The planner, the calendar and sleep readiness work without the assistant. If you only want those, do steps 1, 2, 3 and 6, and skip 4 and 5.

| Step | What you get |
|---|---|
| 1. Android Studio and your phone | Somewhere to build and run the app |
| 2. Google Cloud | Reading your calendars and writing approved blocks into your category calendars |
| 3. Health Connect and Fitbit | Sleep, so readiness has something to measure |
| 4. Anthropic | The key behind the assistant |
| 5. Cloudflare Worker | The small server that holds the key and the monthly cap |
| 6. Build, install, permissions | The app on your phone |

Names that must match exactly: the app's package is `app.planner`, the Worker is `planner-proxy`, and the two settings the app reads from `android/local.properties` are `GOOGLE_WEB_CLIENT_ID` and `WORKER_URL`.

---

## 1. Android Studio and your phone

You need a computer with the repository cloned, an Android phone on Android 9 or newer, and a USB cable.

1. Install [Android Studio](https://developer.android.com/studio) (Ladybug or newer). Its bundled JDK is the right one. If you build from a terminal, use JDK 17 to 21: Kotlin 2.0 does not run on JDK 25.
2. Choose **Open** and pick the `android` folder (not the repository root). Let Gradle sync finish. Studio adds your SDK path to `android/local.properties` for you.
3. On the phone, turn on USB debugging:
   - **Settings, About phone**, tap **Build number** seven times to unlock developer options.
   - **Settings, System, Developer options**, switch on **USB debugging**.
   - Plug the phone in and accept the "Allow USB debugging?" prompt.
4. The phone should now appear in the device menu at the top of Android Studio. You will press Run in step 6.

If the phone does not show up, try a different cable (some only charge), then set the USB mode to File transfer.

## 2. Google Cloud: Calendar access

Planner reads all your calendars. It writes only to your category calendars (Academic, Lectures, Quran and Arabic, Commute, Society, Fitness, Personal), and **only after you approve each change** on an approval card. It never writes to Prayer times or any other calendar, and there is no separate Planner calendar any more.

1. Go to the [Google Cloud console](https://console.cloud.google.com/) and create a project, for example `planner`.
2. **APIs and Services, Library**: search for **Google Calendar API** and click **Enable**.
3. Set up the **OAuth consent screen** (in newer consoles this is under **Google Auth Platform**):
   - User type **External**, publishing status **Testing**.
   - Under **Test users**, add your own Google address. Only test users can sign in while the app is in Testing.
   - Under **Data access** (or **Scopes**), add these two:
     - `https://www.googleapis.com/auth/calendar.events` (read and write events, with your approval in the app)
     - `https://www.googleapis.com/auth/calendar.calendarlist.readonly` (list your calendars to find them by name)

     These replace the old `calendar.readonly` and `calendar.app.created` scopes. If you set up v1 before, remove those two and add these.
4. **Credentials, Create credentials, OAuth client ID**, type **Android**:
   - Package name: `app.planner`
   - SHA-1: the fingerprint of your **debug keystore**. Get it with:

     ```bash
     cd android
     ./gradlew signingReport
     ```

     and copy the `SHA1` line under `Variant: debug`. Or run
     `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`.
   - If you later build on a different computer, its debug keystore has a different SHA-1: add that one as a second Android client, or Google will say it doesn't recognise the app.
5. **Create credentials, OAuth client ID**, type **Web application**, and give it any name (for example `planner-worker`). No redirect URIs are needed. Copy its **client ID**, which ends in `.apps.googleusercontent.com`. This is your `GOOGLE_WEB_CLIENT_ID`. It is only needed for the assistant: it lets the app prove to the Worker that it is you (step 5).

**Testing mode and the seven-day expiry.** While the consent screen is in Testing, Google expires the calendar access after 7 days. Today then shows a **Sign in again** banner; nothing is written and nothing breaks until you do. That takes one tap. If it annoys you, move the consent screen to **In production**: it stays unverified, you will see a "Google hasn't verified this app" warning once, and you can click through it because it is your own app and your own account.

### Calendar names the app looks for

Calendars are found by name (case and surrounding spaces do not matter, so `Academic ` with a trailing space is fine):

| Calendar | Used for |
|---|---|
| Academic, Lectures, Quran and Arabic, Commute, Society, Fitness, Personal | Writable category calendars (see `docs/prompts/calendar-categories.md`) |
| Prayer times | Read-only prayer begin times (the event start is the anchor; weekends are estimated) |

Weekly template tasks are numbered all-day events in the category calendars, for example `1. PintOS (3h)`, `2. Lecture - OS (2h, from 15:00)`, `3. Arabic hw (1h, after Asr)`. See MANUAL.md for the full format.

**Try it on a test calendar first.** Before letting the app write to your real calendars, use a second Google account (or a copy of the calendars with the same names) and run through the checks in "Checking it all works" there.

## 3. Health Connect and Fitbit sharing

Sleep comes from Health Connect, which Fitbit writes into.

1. **Health Connect.** On Android 14 and newer it is part of the phone (Settings, Health Connect). On Android 9 to 13, install **Health Connect by Android** from the Play Store.
2. **Fitbit.** In the Fitbit app, open its Health Connect settings and switch on sharing for **sleep**. (The menu has moved between app versions; search the Fitbit app settings for "Health Connect".) Sleep from last night can take a while to arrive after you wake.
3. **Planner.** After you install the app (step 6), the Today screen has an **Allow** button for Health Connect. Accept the sleep permission. Under **Settings, Notifications** there is also **Allow background sleep reading**: turn it on so the "back to bed" notice and the morning brief can use fresh sleep while the app is closed.

If Health Connect has no sleep for a night, you can enter it by hand from the readiness card on Today. Readiness never scores an incomplete night.

## 4. Anthropic: key and spending limit

1. In the [Anthropic Console](https://console.anthropic.com/), create an **API key** and copy it. You will paste it into Cloudflare in step 5; do not put it in the repository or the app.
2. Set a **monthly spend limit** in the Console's limits settings, a little above what you are happy to pay (the app is designed for roughly £10 to £15 a month). This is the second safety net: the Worker enforces its own cap first (step 5), but the Console limit holds even if the Worker is misconfigured.

## 5. Cloudflare Worker

The Worker holds your Anthropic key, checks that every request comes from your Google account, picks the model, and stops answering once the month's cap is reached. It stores token counts and a monthly total only, never message text.

You need a free [Cloudflare](https://dash.cloudflare.com/sign-up) account and Node.js 18 or newer.

```bash
cd worker
npm ci
npx wrangler login                          # opens the browser to authorise
npx wrangler kv namespace create USAGE      # prints an id: paste it into wrangler.toml
```

Edit `worker/wrangler.toml`:

- `ALLOWED_EMAIL`: the Google address allowed to use the Worker (yours).
- `GOOGLE_WEB_CLIENT_ID`: the **Web** client ID from step 2.
- `MONTHLY_CAP_GBP`: the monthly cap in pounds (default 15).
- the `id` under `[[kv_namespaces]]`: the namespace id printed above.

Then store the key and deploy:

```bash
npx wrangler secret put ANTHROPIC_API_KEY   # paste the key when asked
npx wrangler deploy                          # prints https://planner-proxy.<account>.workers.dev
curl https://planner-proxy.<account>.workers.dev/health    # should print: ok
```

Keep the printed address: it is your `WORKER_URL`.

**Upgrading from v1:** redeploy the Worker (`npm ci && npm test && npx wrangler deploy`). The v2 app sends the model it wants in an `X-Planner-Model` header, which the old Worker ignores. The Worker only accepts models it has prices for.

`worker/src/pricing.ts` holds estimated per-token prices that the cap is worked out from. Check them against Anthropic's pricing page now and then and edit them if they have drifted. If you change the cap, the amount shown in the app (`AiSpendRepository.CAP_GBP`) is a separate constant, so change it too.

To check the Worker's own tests: `npm run typecheck && npm test`.

## 6. First build, install and permissions

Create `android/local.properties` if Studio has not (it is git-ignored) and make sure it has these lines, using your values:

```
GOOGLE_WEB_CLIENT_ID=<the Web client ID from step 2>
WORKER_URL=https://planner-proxy.<account>.workers.dev
```

`sdk.dir=...` is added by Android Studio and should stay. After editing this file, rebuild: the values are baked into the app at build time. Leaving them blank still builds, and the assistant then says it isn't set up.

Pick your phone in the device menu and press **Run**. The first build downloads a lot and takes several minutes.

### What to allow, and where

| Step | Why | Where |
|---|---|---|
| Sign in to Google | Read your calendars, and write the changes you approve | Settings → Google → **Sign in**. If you see "Google hasn't verified this app", choose Advanced, then continue. |
| Allow notifications | Start and end check-ins, prompts | The check-in setup card on Today, or Settings → Check-ins |
| Allow exact alarms | Check-ins on the minute, not minutes late | Same card: opens **Alarms and reminders** |
| Battery: Unrestricted | Battery savers delay alarms and background syncs | Same card: opens the battery setting |
| Allow Health Connect | Sleep | Settings → Sleep |
| Allow usage access | Social-media minutes | Settings → Social time (Android has no pop-up for this one) |
| Sign in for the assistant | Proves to the Worker that it is you | Ask, on first use, with the account named in `ALLOWED_EMAIL` |

Then open **Backlog → Import from calendar** to read your weekly template.

## Checking it all works

Do this first against a **test calendar** (SETUP step 2), then against your real ones.

1. Today shows your day between the prayers, with fixed events and any timed blocks. "Synced" shows a time.
2. Backlog → Import from calendar: numbered all-day events become tasks; anything unreadable is under Needs a look.
3. Plan today (or Plan on a task): an approval card appears and **Google Calendar is unchanged**. Approve: the report says Verified, and in Google Calendar the all-day item is now the timed block, with a description such as "Task: 3h. Includes 15 min for Dhuhr."
4. Undo from the snackbar: Google Calendar is back as it was.
5. Edit a proposed event in Google Calendar before approving: the card reports Stale and writes nothing.
6. In Ask, send "add 2h internship apps Sunday": a Review card appears and nothing changes until you approve.
7. Wait for a block's start: the check-in arrives with Start / Snooze 10 / Skip.
8. On a device or emulator, `cd android && ./gradlew :app:connectedDebugAndroidTest` checks the on-phone database against the same rules as the tests.

The full hand-test lists per area are in `docs/redesign/notes/` (B: calendar and approval, C: assistant, D: screens, E: check-ins, F: data, G: sleep). MANUAL.md ends with troubleshooting keyed to the messages the app shows.

## Commands

| What | Command |
|---|---|
| Worker tests | `cd worker && npm ci && npm run typecheck && npm test` |
| Pure-Kotlin tests without the Android SDK | `android/scripts/jvm-test.sh` (or name modules: `core engine calendar assistant`) |
| App build and all tests | `cd android && ./gradlew :core:scheduler:test :engine:test :calendar:test :assistant:test :app:assembleDebug :app:testDebugUnitTest` |
| On-device database tests | `cd android && ./gradlew :app:connectedDebugAndroidTest` |
| Install the smooth (release) build on your phone | `cd android && ./gradlew :app:installRelease` (use this for everyday use; Run in Android Studio installs the slower debug build) |
