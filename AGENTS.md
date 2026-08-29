# PaidIn Android agent instructions

PaidIn Android is a native Kotlin/Jetpack Compose companion for the PaidIn job-discovery project.

## Product rules
- Human review stays in control; do not implement mass auto-application.
- Keep source-specific identifiers as provenance, not global duplicate identity.
- Unknown salary/location/requirements stay unknown.
- Hard qualifiers, hard disqualifiers, and preferences remain user-editable.
- Prefer server-side collection adapters over scraping job sites on-device.

## Engineering
- Kotlin + Jetpack Compose; package `com.birdmachine.paidin`.
- Keep the app useful offline.
- Maintain Android Share-sheet URL intake.
- Preserve Ocean Dolphin Aero: vivid ocean, clear aqua glass, high contrast, accessible controls.
- Do not commit secrets or machine-local SDK paths.
- Build with JDK 17 and Gradle 8.9.

## Verify
Run `./gradlew assembleDebug` or let GitHub Actions build the debug APK.
