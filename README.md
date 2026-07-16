# VoiceAsset Android

Native Android client for the VoiceAsset self-hosted voice asset platform. This repository currently contains the Phase 0 application foundation: a Kotlin, Jetpack Compose, Material 3, single-activity app that reports its real startup state and clearly indicates that no server has been configured.

## Requirements

- JDK 21
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0

Create `local.properties` with `sdk.dir=C:\path\to\Android\Sdk`, or set `ANDROID_HOME`. Do not commit `local.properties`.

## Build and test

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

On Linux or macOS:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Run Compose instrumentation tests on a connected API 26+ device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/`. Server profiles, recording, offline storage, and reliable upload will be implemented as tested vertical slices; the current UI does not claim those capabilities are available.

Run `./gradlew ktlintCheck` to verify Kotlin formatting. Generate the resolved
dependency SBOM with `./gradlew :app:cyclonedxDirectBom`; CI also verifies that its runtime
components have recognized licenses.

## Architecture baseline

The package namespace is `com.voiceasset.android`, `minSdk` is 26, and Java/Kotlin compilation uses a Java 21 toolchain. Android communicates only through the public VoiceAsset Server API. Provider credentials and server-side business logic must never be added to this client.

The client targets Server OpenAPI contract `0.1.0`, recorded in
`CONTRACT_VERSION`. Update the compatibility matrix before changing that pin.

## License

VoiceAsset Android is licensed under AGPL-3.0-or-later. See `LICENSE`.
