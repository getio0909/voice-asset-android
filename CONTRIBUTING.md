# Contributing

Thank you for improving VoiceAsset Android.

## Development workflow

1. Use JDK 21 and install Android SDK Platform 37.
2. Keep changes focused and add tests for behavior changes.
3. Run `./gradlew test lintDebug assembleDebug` before opening a pull request.
4. Run `./gradlew connectedDebugAndroidTest` when a device or emulator is available.

Before a release candidate, also run `./gradlew ktlintCheck lintRelease
assembleRelease bundleRelease :app:cyclonedxDirectBom`, then execute both
release scripts documented in `README.md`. Release keys and passwords remain
outside Gradle, GitHub Actions, and the repository.

Use four-space indentation for Kotlin, `PascalCase` for types and composables, and `camelCase` for functions and properties. Format code with Android Studio's Kotlin formatter and resolve Android lint findings instead of suppressing them without an explanation.

Commit messages follow Conventional Commits, for example `feat(android): add server profile validation`. Pull requests should describe user-visible behavior, link the relevant issue, list commands run, and include screenshots for UI changes. API work must identify the VoiceAsset OpenAPI contract version it targets.

Never commit tokens, signing keys, real server data, `local.properties`, or local SDK paths. Report security issues privately as described in `SECURITY.md`.
