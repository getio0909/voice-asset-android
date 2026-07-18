# Changelog

All notable changes to this project will be documented in this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases use [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Added repository-outside RSA-4096 release signing with GitHub Actions Secret
  restoration, signed APK/AAB checksum verification, and same-repository PR
  release artifacts. The keystore and passwords remain outside Git.
- Gate Profile selection and manual remote refresh on the same readable
  Keystore session policy. Selecting a saved but signed-out Profile now keeps
  its cached/local data available without scheduling network work; an explicit
  Android regression test covers both entry points.
- Recover valid MediaRecorder `.m4a` files after process death by probing
  duration and recomputing immutable file metadata; unreadable or empty media
  still becomes an explicit interrupted failure. JVM coverage now exercises
  both the readable-M4A and failed-recovery paths.
- Gate immediate post-recording sync on the same readable Keystore session
  policy as process-start recovery. A configured but signed-out or unreadable
  Profile now leaves the finished recording local without scheduling a remote
  task; the refreshed JVM policy test covers both paths.
- Gate process-start remote recovery on a readable Keystore session. Profiles
  without a session, or with an unreadable credential, remain entirely local;
  the new JVM policy test covers authenticated, missing, and corrupt stores.
- Advanced the target contract to `0.22.0`; the explicit `0.13.0` through
  `0.22.0` upload/transcription compatibility subset remains fail-closed.
  The home screen continues to place local recording and playback before
  optional server synchronization.
- Put the local recording controls and local recording library before all
  server status, login, and synchronization sections on the home screen.
  Opening the app now communicates and preserves the offline-first path even
  more directly; server sync and transcription remain optional.
- Advanced the target contract to `0.20.0`. Personal terminal-job
  notifications remain an interactive Session-only Server feed and add no
  Android permissions, background work, persisted state, or user-visible UI;
  the explicit `0.13.0` through `0.19.0` sync subset remains compatible. JVM,
  Ktlint, Debug/Release Lint, Debug/Release APK, AAB, and compilation of 41
  instrumentation methods pass. The 14,619,252-byte V2 development-signed APK
  is
  `VoiceAsset-0.1.0-dev-contract-0.20.0-personal-notifications-debug-20260718T085854Z.apk`
  with SHA-256
  `7eb84ec921b27140b151cd3bfe2bcb8136e5837c67718de1d916721ebcbadfd2`.
- Advanced the target contract to `0.19.0`. The deployment System Settings
  projection remains Console-only and adds no Android authority or persisted
  state; the explicit `0.13.0` through `0.18.0` sync subset remains compatible,
  while new pairing payloads fail closed on the current contract. All 134 JVM
  tests, Ktlint, Debug/Release Lint, APK/AAB assembly, and compilation of 41
  instrumentation methods pass. The 14,619,252-byte V2 development-signed APK
  is
  `VoiceAsset-0.1.0-dev-contract-0.19.0-device-sessions-reconnect-debug-20260718T071900Z.apk`
  with SHA-256
  `82708cc07bf0b8c148dfaf951314e111ff480b37d5800a7abdbcbfe2e4845a57`.
- Persist the complete access/refresh session and both expiries inside the
  existing Android Keystore AES-GCM envelope. Authenticated API construction
  now rotates an expiring access token once under a mutex, writes both rotated
  credentials atomically, and removes unusable local state after a rejected
  refresh. Legacy access-only installations remain readable but require a new
  login or pairing after that access token expires.
- Added personal device-session inventory and revocation for the active Server
  Profile. Compose receives credential-free rows only; revocation requires a
  second explicit confirmation, reloads the Server inventory before deleting
  the exact session UUID, and removes the local Keystore session only after the
  Server confirms current-device revocation. **Reconnect current server** can
  replace the encrypted session on that exact Profile without splitting its
  offline identity. The password leaves UI state before network I/O and is
  never persisted; rejected authentication leaves any stored session unchanged.
  The full local gate now passes 134 JVM tests, Lint, Debug/Release APK assembly,
  and compilation of 41 instrumentation methods. The 14,619,252-byte V2
  development-signed APK is
  `VoiceAsset-0.1.0-dev-contract-0.18.0-device-sessions-reconnect-debug-20260718T062602Z.apk`
  with SHA-256
  `9253a623451db78596d1f645b4bb038d8f6e07f4b0423f82e5200ea6481ae1ec`.
- Advanced the target contract to `0.18.0` and added dependency-free one-time
  device pairing. The strict parser accepts only the versioned HTTPS origin,
  current contract, five-minute expiry, canonical UUID, and 32-byte secret;
  the app clears the pasted payload before claiming, stores the resulting
  session only through Android Keystore, and removes it if profile persistence
  fails. Contracts `0.13.0` through `0.17.0` remain capability-checked for the
  existing upload and synchronization subset. All 117 JVM tests, Lint, the
  Debug APK build, and compilation of 35 instrumentation methods pass. The
  14,351,328-byte V2 development-signed APK is
  `VoiceAsset-0.1.0-dev-contract-0.18.0-device-pairing-debug-20260718T044721Z.apk`
  with SHA-256
  `456ab75b011ba0d4b521ff945eb2f806004003469efd271edb7577b7ac9f3d9f`.
- Advanced the target contract to `0.17.0` and added bounded mobile retry for
  eligible failed Server jobs. The app posts only the exact job UUID, uses the
  Keystore-backed session, rejects inconsistent responses, serializes retry
  against other administration writes, and updates job/system counts without
  exposing payloads, worker identity, or credentials. Contracts `0.13.0`
  through `0.16.0` remain an explicit capability-checked compatibility subset.
  The full local gate passes 112 JVM tests and compiles 32 instrumentation
  methods. The 14,348,236-byte v2 development-signed replacement APK has
  SHA-256
  `5a5afed75d841ddef861e58fdf30b1f6a60b8323790414a3792288d6d10965c2`.
- Added bounded mobile administration over the public Server API. Owners and
  administrators can explicitly load workspace system status, the 20 most
  recent credential-free jobs, and existing ASR/LLM Profiles, then enable or
  disable a Profile with an exact resource-version `If-Match`. An explicit
  health action calls the family-specific endpoint and retains only the safe
  status, error class, and check time. Strict response
  validation, Keystore session handling, permission/conflict errors, and a
  Compose management card are covered without exposing credentials or general
  server commands. A real `0.13.0`/10443 Mock LLM check returned `healthy`,
  revoked its temporary session, and left both Caddy services active with zero
  restarts. The current full local gate is recorded above.
- Added a backward-compatible remote-asset catalog bootstrap for explicitly
  supported servers that do not advertise `incremental_sync`, including the
  deployed `0.13.0` test server. The typed client validates stable workspace,
  ordering, identifiers, timestamps, cursor bounds, and response shape;
  WorkManager follows up to 100 pages and Room merges versions monotonically
  without resurrecting tombstones or inferring deletions from an old catalog.
  Profile save/switch and a new **Refresh server assets** action enqueue the
  unique pull. A credential-redacted strict-TLS smoke confirmed the real 10443
  endpoint returns a compatible paginated catalog. The current full local gate
  is recorded above.
- Added cached-asset metadata editing for title, language, and nullable
  Collection assignment. Android reads the latest strong ETag, sends an exact
  `If-Match` full replacement, requires reload after conflicts or ambiguous
  network failures, and refreshes the Room row without moving its incremental
  cursor. Version-monotonic cache writes prevent stale pages from regressing a
  successful edit.
- Added a unified, case-insensitive offline-library search across cached asset
  title/ID/language/status fields and local recording filename/ID/status/error
  fields. Total and matching counts stay distinct, each Compose list remains
  capped at 50 results, and controls remain visible when the active playback row
  is filtered out.
- Added integrity-verified in-app playback for saved local recordings with
  play, pause, resume, stop, completion, and retry states. Playback verifies the
  Room identity, canonical path, byte length, and SHA-256 before MediaPlayer
  preparation; it keeps one active engine, handles audio focus, and pauses on a
  noisy-output broadcast.
- Added user-initiated local recording export through a non-exported, read-only
  content provider. Export accepts only flat M4A/WAV names under the recording
  directory, verifies Room size and SHA-256 metadata before granting a URI, and
  rejects write mode, path traversal, missing files, and corrupted bytes.
- Added per-recording upload and batch-transcription policy snapshots. The next
  recording can inherit either server default or override it; Room migration 4
  persists both nullable choices so restart, retry, and explicit manual actions
  resolve the same policy. Migrated rows continue to inherit server defaults.
- Added per-profile upload and batch-transcription policy controls. WorkManager
  now runs upload and transcription as independently constrained stages, while
  manual policies expose explicit **Start upload** and **Start transcription**
  actions without replaying completed uploads.
- Advanced the target contract to `0.16.0` and added the strict typed
  incremental asset-change API. Contracts `0.13.0` through `0.15.0` remain an
  explicit capability-checked compatibility subset for existing upload and
  transcription behavior.
- Added Room migration 2 with Server-Profile-scoped remote assets, durable
  deletion tombstones, and compare-and-set cursors. WorkManager applies bounded
  pages atomically only when `incremental_sync` is advertised and chains a pull
  after upload. The Compose home screen observes the active profile's cache and
  renders the 50 most recently updated assets for offline confirmation. JVM
  paging tests, instrumentation persistence/migration/Worker/UI tests, Ktlint,
  Lint, and the v2-debug-signed replacement APK gate pass; device execution
  remains open.
- Added an explicit multi-server switch action. The active profile now selects
  recording, transcript, and offline asset state together; switching is disabled
  during capture and recording transitions.
- Added a per-profile local recording list showing the 50 most recent filenames,
  duration, capture state, upload progress, sync/transcription state, offline
  transcript availability, and persisted error code. The full local count remains
  visible without creating an unbounded Compose tree.
- Added Room migration 3 and an explicit failed/blocked sync retry action. Manual
  retry reconstructs the last durable asset/upload checkpoint, resets transient
  attempt state, and advances a persisted generation so a failed transcription
  receives a new idempotency key while asset and upload keys remain stable. The
  full local gate now passes 80 JVM tests and compiles 24 instrumentation tests.
- Advanced the fail-closed Server contract pin to `0.15.0` and added a strict,
  redacting authenticated password-change API boundary. The app does not expose
  this account workflow until local credential cleanup is wired into its UI.
- Advanced the fail-closed Server contract pin and compatibility fixtures to
  `0.14.0`. Workspace profile and membership management remain Server/Console
  workflows and do not change Android recording, Room, WorkManager, or
  real-time fallback semantics.
- Advanced the fail-closed Server contract pin and compatibility fixtures to
  `0.13.0`. The additive administration read models are Console-facing and do
  not change Android recording, Room, or WorkManager sync semantics.

- Advanced the fail-closed Server contract pin and compatibility fixtures to
  `0.12.0`. Owner-only permanent deletion is an additive Server/Console workflow
  and does not change Android recording, Room, or WorkManager sync semantics.
- Advanced the fail-closed Server contract pin and compatibility fixtures to
  `0.11.0`. Asynchronous waveform delivery is additive Server/Console behavior
  and does not change Android recording, Room, or WorkManager sync semantics.
- Re-ran `gradlew.bat test lint assembleDebug` with the accepted Google SDK at
  `C:\tools\Android\Sdk`; all 74 tasks completed successfully after explicitly
  copying the persisted user SDK path into the current build process.
- Advanced the fail-closed Server contract pin and compatibility fixtures to
  `0.10.0`. Full-text asset search and Provider/Speaker filters are additive
  Server/Console/MCP behavior and do not change Android recording or sync.
- Advanced the fail-closed Server contract pin and all compatibility fixtures
  to `0.9.0`. The additive browser catalog filters and asset lifecycle endpoints
  do not change Android sync behavior. All-module tests, lint, and Debug APK
  assembly pass with the installed Google Android SDK.
- Extended the Tag candidate to build and validate both unsigned APK and AAB
  outputs through repository-local packaging and verification scripts. Release
  signing remains an external, secret-free human gate.
- Made CI and Tag validation run the all-module Gradle `test` aggregate so core
  API/model tests cannot be skipped by the app-only unit-test task.
- Migrated Compose instrumentation rules to the v2 coroutine-test APIs and added
  the serialization library as an explicit instrumentation-test dependency.
- Enabled Android lint models for both core modules; reviewed custom trust-manager
  findings are now backed by tests proving certificate pins cannot bypass chain
  validation.
- Raised `compileSdk` and the CI/Release toolchain to stable Android 17 API 37
  and Build Tools 37.0.0 for the current Lifecycle/Compose AAR requirements.
  `targetSdk` remains 36 and `minSdk` remains 26, so this does not opt the app
  into Android 17 runtime behavior or drop supported devices.
- Advanced the fail-closed Server contract pin to `0.8.0`; the strict OkHttp
  boundary now validates access and refresh cookies and supports native rotation.
- Advanced the fail-closed Server contract pin to `0.7.0`; additive
  organization reads do not change Android runtime behavior yet.
- Advanced the fail-closed Server contract pin to `0.6.0`; scoped Agent API
  keys do not change Android runtime behavior.
- Advanced the fail-closed Server contract pin to `0.5.0`; the additive MCP
  asset-search endpoint does not change Android runtime behavior.

- Advanced the Server OpenAPI pin to `0.4.0` for managed ASR provider and hotword
  originals.

### Added

- Added a strict protocol-v1 authenticated WebSocket client, bounded local
  replay buffer, lost-ACK reconciliation, reconnect/backoff, heartbeat, and
  final-result consistency handling for the future real-time recording mode.
- Added local-first 16 kHz mono PCM capture with checkpointed WAV archives. A
  coordination layer degrades network or sequence failures to local capture,
  and cold-start recovery repairs valid interrupted WAV files into immutable
  saved recordings so existing WorkManager policy can upload them. The mode is
  not exposed in the UI until the Server WebSocket endpoint is integrated.

- Versioned Room schema `1` and explicit cloud-backup/device-transfer exclusions
  for recordings, credentials, preferences, and local database state.
- A Tag-triggered draft release pipeline that validates the version, tests and
  lints, emits explicitly unsigned APK/AAB candidates, a CycloneDX SBOM, and
  complete SHA-256 checksums, and requires human signing/device gates before
  publication.
- Validated multi-server profiles, Room recording persistence, Keystore-backed
  credentials, a foreground M4A recording service, and explicit recovery states.
- A strict JVM-tested OkHttp API client with structured errors, resumable upload
  planning, custom CA trust, and full certificate SHA-256 verification.
- Authenticated profile setup that negotiates Server capabilities before saving
  a Keystore-protected session; account passwords are never persisted.
- Durable Room sync checkpoints and constrained WorkManager orchestration that
  resumes server-recorded parts and queues transcription after upload.
- Stable per-recording idempotency keys, byte-verified server-part recovery,
  transcription job polling, and an offline Room cache for immutable transcript
  revisions displayed by the Compose UI.

- Initial Kotlin and Jetpack Compose single-activity application.
- Material 3 startup screen with initialized and server-not-configured states.
- Unit, Compose UI test, lint, Gradle wrapper, and CI foundations.
- Ktlint formatting, emulator instrumentation CI, and a dependency-level
  CycloneDX SBOM with license-policy validation.
- Contract pin, architecture baseline, and API 26 decision record.

### Fixed

- Check server capabilities before creating a device session, preventing
  incompatible profile attempts from leaving an unused remote login. The app
  now explicitly accepts the verified `0.13.0`–`0.16.0` upload/transcription
  subset while continuing to reject unknown contracts and missing features.
- Preserve the retained WAV file timestamp before repairing its RIFF lengths so
  process-death recovery does not misreport the app restart time as capture stop.

- Use the API 30 foreground-service microphone constant only on API 30+, render
  server counts with plurals, and provide monochrome adaptive launcher icons.
- Keep the WorkManager instrumentation fake aligned with contract `0.8.0` and
  its required `refresh_sessions` capability instead of failing compatibility
  before sync assertions run.
- Keep transcription sync pending until a matching immutable revision is
  fetched and cached; queued jobs are no longer reported as complete.
- Reject server-recorded upload parts whose SHA-256 does not match the exact
  local byte range, including after a lost response or process restart.
- Treat Gradle subprojects as first-party SBOM components only when both their
  root group and CycloneDX `project_path` identify this build; external
  dependencies still require reviewed license metadata.

- Keep Android 16 runtime targeting independent from the Android 17 compile SDK,
  resolving current AndroidX AAR metadata without changing runtime opt-in.
- Let the edge-to-edge runtime configure navigation bar icon contrast so API 26
  builds do not reference the API 27-only theme attribute.
- Enable KVM access for the ephemeral Linux CI runner so the API 35 emulator
  boots within the instrumentation-test timeout.
- Restore the Gradle wrapper's executable bit before Linux emulator tests.
