# VoiceAsset Android

Native Android client for the VoiceAsset self-hosted voice asset platform. The
app is offline-first: it opens to local recording and playback without login or
network access; a server profile is optional and adds synchronization. Startup
only resumes remote work for profiles with a locally protected session, so an
unconnected profile never turns launch into a sign-in or network requirement. The
current candidate includes validated, switchable server profiles, authenticated capability
negotiation, Room-backed recording/upload checkpoints and per-profile incremental
asset caches surfaced as bounded, searchable offline lists, Keystore-protected
rotating sessions with same-Profile reconnect and explicit per-device
inventory/revocation, foreground M4A
capture, resumable WorkManager upload, job polling, and
bounded local recording history with upload/transcription state and explicit
failed-sync retry, per-server upload/transcription policies, manual stage controls,
per-recording policy snapshots, ETag-protected cached-asset metadata editing,
bounded mobile administration for workspace status, recent jobs, eligible
failed-job retry, and exact-version ASR/LLM Profile enablement plus explicit
credential-free health checks,
integrity-verified local playback and
user-initiated audio export, plus offline transcript display. An unexposed real-time core adds authenticated
WebSocket replay/reconnect, local-first AudioRecord/WAV capture, and recoverable
batch-upload fallback; it remains hidden until the Server endpoint is complete.
Current app sources still require an Android
device run before this path is accepted for release.

## Requirements

- JDK 21
- Android SDK Platform 37.0
- Android SDK Build Tools 37.0.0

Create `local.properties` with `sdk.dir=C:\path\to\Android\Sdk`, or set `ANDROID_HOME`. Do not commit `local.properties`.

## Build and test

On Windows:

```powershell
.\gradlew.bat test lintDebug assembleDebug
```

On Linux or macOS:

```bash
./gradlew test lintDebug assembleDebug
```

Run Compose instrumentation tests on a connected API 26+ device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

On Windows, an x86_64 AVD also requires a supported acceleration backend. If
the Google Android Emulator Hypervisor Driver package is installed, run its
installer from an elevated PowerShell session, then verify acceleration before
starting the AVD:

```powershell
& "$env:ANDROID_HOME\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat"
& "$env:ANDROID_HOME\emulator\emulator-check.exe" accel
```

Installing this driver is a machine-level administrator action. Do not treat a
compiled `androidTest` APK as an executed instrumentation result.

The debug APK is written to `app/build/outputs/apk/debug/`. The current source
passes all-module JVM tests, APK assembly, and lint locally; instrumentation
additionally requires a connected API 26+ device or emulator.

Build a release candidate with the repository-outside signing key configured:

```bash
./gradlew test ktlintCheck lintRelease assembleRelease bundleRelease \
  :app:cyclonedxDirectBom
bash scripts/package-release.sh v0.1.0 dist-release
bash scripts/verify-release.sh v0.1.0 dist-release
```

Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
and `ANDROID_KEY_PASSWORD` in the shell before building. The release directory
contains a signed APK and AAB, a CycloneDX SBOM, and complete SHA-256 checksums.
The Tag workflow restores the same key from GitHub Actions Secrets; see [the
release signing guide](docs/release-signing.md). Never add a keystore or
password to the repository.

The current JVM suite contains 134 passing tests. It covers recording and sync
state machines, strict TLS/API parsing, stable generation-scoped retry keys, exact upload-part
recovery, real-time protocol/replay/reconnect and WAV recovery, transcription
terminal-state handling, incremental paging, backward-compatible asset-catalog
bootstrap, offline transcript/asset model
validation, encrypted access/refresh session rotation, same-Profile session
replacement, exact device-session revocation, exact asset ETag replacement and
cache refresh, case-insensitive
offline-library filtering, credential-free administration reads, bounded
failed-job retry, exact-version
Provider Profile state changes, explicit safe health checks, recording-file integrity,
and playback/audio-focus lifecycle. Room,
WorkManager, Keystore, recording-service, MediaPlayer, and Compose behavior remain
device instrumentation gates.

## Physical-device acceptance

Install the newest debug APK over the existing debug build. First leave the
server form blank, launch with network disabled, record at least ten seconds,
stop, play, and export the local file. Then optionally test synchronization
against `https://api.getio.net:10443` with custom CA left blank. Prefer scanning
the QR code for the one-time pairing URI created on the Console **Device
sessions** page; you can also paste it when the device has no Google Play
services. Pairing does not require entering an account password in Android.
Confirm that the new
Profile shows **This device**, restore connectivity, and verify upload plus
transcript sync.

Then revoke **This device** with the second confirmation. Use **Reconnect current
server** with the same credentials used for Console login and verify that the
same Profile, offline recording count, and settings remain. The repository has
no default account password. Report the failing step, visible error text, device
model, Android version, and approximate UTC time; never include the password,
pairing URI, session identifiers, or tokens in screenshots or logs.

Run `./gradlew ktlintCheck` to verify Kotlin formatting. Generate the resolved
dependency SBOM with `./gradlew :app:cyclonedxDirectBom`; CI also verifies that
its runtime components have recognized licenses.

## Architecture baseline

The package namespace is `com.voiceasset.android`, `minSdk` is 26, `compileSdk`
is 37, `targetSdk` remains 36, and Java/Kotlin compilation uses a Java 21
toolchain. Android communicates only through the public VoiceAsset Server API.
Provider credentials and server-side business logic must never be added to this
client.

The client targets Server OpenAPI contract `0.22.0`, recorded in
`CONTRACT_VERSION`. Capability negotiation explicitly accepts the verified,
backward-compatible `0.13.0` through `0.22.0` upload/transcription subset; all other
contract versions fail closed before login. Update the compatibility matrix
before changing this policy.

Saved profiles can be switched from the home screen. Switching is disabled while
a recording transition or capture is active so one session cannot move between
servers. A recording made without a profile remains device-local and visible in
the library; the selected profile controls server-bound recording, transcript,
sync, and remote-asset views.

Login and pairing persist the access credential, refresh credential, and both
expiries together inside one Keystore-backed AES-GCM envelope. API calls rotate
an expiring access credential once and replace the complete encrypted session;
a rejected or expired refresh removes the unusable local session. **Device
sessions** explicitly loads the selected account's credential-free inventory.
Each revoke requires a second confirmation and a fresh Server read of the exact
UUID; revoking **This device** deletes local credentials only after remote
success. Upgraded access-only records remain supported until expiry, then the
profile must be signed in or paired again.

Compatible `0.13.0`–`0.15.0` servers without `incremental_sync` are no longer
left with an empty asset cache. WorkManager strictly validates and follows the
stable `GET /api/v1/assets` cursor from the beginning, merges at most 10,000
catalog rows without regressing a newer Room version or tombstone, and never
treats absence from the legacy list as a deletion. Newer servers continue to
use the durable change cursor. Saving or selecting a profile schedules a pull,
and **Refresh server assets** queues the same unique background work explicitly.

Non-trashed cached assets expose **Edit metadata**. The editor first reads the
latest resource and strong ETag, then submits the complete title, language, and
nullable Collection replacement with that exact `If-Match`. A conflict never
overwrites the newer server value: the user must reload before editing again.
Successful responses refresh only that Room row without advancing the
incremental cursor, and stale pages cannot regress a newer cached version.

The **Mobile administration** card is loaded only on explicit refresh with the
active profile's Keystore-protected session. It shows a bounded recent-job page,
workspace asset/storage/transcript/job counts, and credential-free ASR/LLM
Profiles. Accounts with `admin:write` can enable or disable an existing Profile;
each mutation sends the displayed resource version in `If-Match`, rejects stale
updates, and never exposes credentials, SSH, shell, or arbitrary commands. Each
Profile also exposes an explicit **Check health** action. The app retains only
the safe `healthy`/`unhealthy` classification, optional error class, and check
time; vendor messages and credentials never enter Compose state.

Failed or blocked local sync rows expose **Retry sync**. Room migration 3 keeps a
manual-retry generation, reconstructs the last durable upload checkpoint, and
uses a new transcription idempotency key without recreating an existing asset or
upload.

Before capture, the recording card can inherit both policies from the active
server or override either one for the next recording. Room migration 4 stores
those nullable overrides with the recording session. The snapshot therefore
survives process restart, retry, and manual stage actions; migrated recordings
keep `null` and continue to inherit their server defaults.

New profiles select default upload and batch-transcription policies independently.
Each recording resolves its stored override first and then the profile default;
each stage receives its own WorkManager network/charging constraints. A manual upload
stays pending until **Start upload** is selected; a manual transcription stays at
the durable uploaded checkpoint until **Start transcription** is selected. The
real-time policy remains hidden until its Server adapter and device E2E are ready.

The offline-library search filters both cached Server assets and local recordings
without network access. It matches cached title/ID/language/status and local
filename/ID/status/error fields case-insensitively, reports total and matching
counts separately, and still renders at most 50 results per list. Playback
controls remain available if a search hides the active recording.

Saved local recordings expose **Play** and **Export**. Both paths verify the
Room identity, canonical file location, byte length, and SHA-256 before reading
audio. Playback keeps one MediaPlayer active, supports pause/resume/stop, handles
audio-focus loss, and pauses when headphones disconnect. Export opens Android's
share chooser through a non-exported, read-only content provider that grants
access only to flat `.m4a` or `.wav` names under `files/recordings/`; it never
shares an internal filesystem path, accepts write mode, or bypasses a failed
integrity check.

For the current test deployment, create a profile with
`https://api.getio.net:10443` and leave custom CA and certificate fingerprint
blank; the independent gateway now presents a system-trusted public certificate.
Use custom CA only for a different private-CA deployment, never to bypass TLS.

## License

VoiceAsset Android is licensed under AGPL-3.0-or-later. See `LICENSE`.
