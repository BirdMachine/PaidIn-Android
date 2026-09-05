# PaidIn Android + Scout 🐬🌊

PaidIn is a job-discovery and review cockpit with two interchangeable front doors:

- a native Kotlin / Jetpack Compose Android app
- a responsive browser portal served by the always-on PaidIn Scout service

The Scout service does the scheduled job hunting in the cloud, so Mallard, Kestrel, and the Android app can all be powered off and the morning scan still happens.

## Current Scout MVP

- Ocean Dolphin Aero Android UI
- Cloud-backed job feed with local Android caching for offline review
- Manual **Run Scout** and **Refresh** controls in Android
- Responsive web portal for phone, tablet, or desktop review
- Save / Approve / Reject state shared through the Scout API
- Editable search brief in the web portal
- Daily scheduled job discovery
- OpenAI Responses API built-in web search instead of site-specific scraper maintenance
- Structured normalized listings with score, fit summary, provenance URL, salary/location unknown handling, and skills
- Cloudflare Worker API + static portal
- Cloudflare D1 persistence
- Android Share-sheet intake remains available for manually spotted listings
- GitHub Actions checks both Android and Scout TypeScript

The server-side implementation lives in [`scout/`](./scout/README.md).

## Android build

Requires JDK 17 and Android SDK 35.

```bash
./gradlew assembleDebug
```

The APK will be at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

If you don't have Gradle installed, the included `gradlew` bootstrap downloads Gradle 8.9 into `.gradle-dist/` on first use.

## Android connection

After Scout is deployed, open **PaidIn → Settings → Cloud Scout** and enter:

1. the HTTPS Worker base URL
2. the same `SCOUT_TOKEN` configured as a Worker secret

The OpenAI API key is never stored in the Android app or browser.

## Browser flow

Open the deployed Scout URL from any device. Enter the Scout access token once, save the search brief, and review the exact same cloud queue the Android app sees.

## Human-in-the-loop rule

PaidIn discovers, scores, explains, and organizes. Save / Approve / Reject remains human-controlled; the project does not mass auto-apply.
