# PaidIn Android 🐬🌊

Native Android review cockpit for PaidIn, built with Kotlin + Jetpack Compose.

## Current MVP

- Ocean Dolphin Aero UI with a clean, UI-free ocean/dolphin wallpaper
- Seeded review queue and match scores
- Save / Approve / Reject state persisted locally
- Editable Market Dial rules (qualifiers, disqualifiers, preferences)
- Android Share-sheet intake: share a job URL from your browser directly into PaidIn
- Configurable PaidIn API base URL, ready for sync wiring
- Offline-first behavior for the current local feature set
- GitHub Actions debug APK build

## Build

Requires JDK 17 and Android SDK 35.

```bash
./gradlew assembleDebug
```

The APK will be at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

If you don't have Gradle installed, the included `gradlew` bootstrap downloads Gradle 8.9 into `.gradle-dist/` on first use.

## On-device flow

Open PaidIn to browse the seeded queue. From Chrome/Firefox/another browser, use **Share → PaidIn** on a job URL to create a pending-extraction entry locally.

The next development slice is API sync with the existing FastAPI PaidIn server, followed by real normalized job ingestion and Room-backed persistence.
