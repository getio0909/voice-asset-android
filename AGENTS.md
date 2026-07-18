# Repository Guidelines

## Structure

This is a native Kotlin/Compose application. Keep production code under
`app/src/main`, JVM tests under `app/src/test`, and device tests under
`app/src/androidTest`. Preserve the single-Activity, unidirectional-state
foundation and keep Server access behind a typed public API client.

## Commands

- `gradlew.bat test`: run app and core-module unit tests on Windows.
- `gradlew.bat lintDebug`: run Android lint.
- `gradlew.bat assembleDebug`: build the debug APK.
- `gradlew.bat assembleRelease bundleRelease`: build a signed release APK/AAB
  when the four `ANDROID_*` signing variables are set; package and verify them
  with the scripts under `scripts/`.
- `./gradlew test lintDebug assembleDebug`: run all checks on Unix.

Use four-space indentation, `PascalCase` types and composables, and `camelCase`
members. Model recording, upload, and synchronization as explicit states; test
process death, retry, and interruption behavior when those features land.

Use Conventional Commits such as `feat(recording): persist active session`.
Pull requests must list emulator/device coverage and include screenshots for UI
changes. Never commit tokens, signing keys, `local.properties`, recordings, or
personal transcript fixtures. Credentials belong in Android Keystore, and no
permanent “ignore TLS errors” option is allowed.
