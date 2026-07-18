# Android Architecture Baseline

The app is a single-activity Jetpack Compose application targeting API 26 and
Server contract `0.22.0`. UI state is immutable; Room is the offline source of
truth for server profiles, recording lifecycle checkpoints, device-local
recordings, and the per-profile remote asset cache.

The current candidate uses a foreground `MediaRecorder` service for M4A capture, direct Android
Keystore AES-GCM for server credentials, and a strict OkHttp client with custom
CA plus full certificate-fingerprint validation. Login validates both rotated
session cookies, while a credential-free capability preflight rejects unknown
contracts before a device session is created. The target contract is `0.22.0`;
the verified `0.13.0` through `0.22.0` upload/transcription subsets remain explicit
compatibility entries rather than an open-ended semver range. The API boundary can refresh tokens without returning token
material in JSON. Room migration 2 adds remote asset snapshots, permanent-delete
tombstones, and a compare-and-set incremental cursor. Each page and cursor commit
atomically, while periodic WorkManager pulls run only when the Server advertises
`incremental_sync`; older compatible servers use a bounded stable-catalog
bootstrap without advancing that cursor or inferring deletion. Room also
persists upload checkpoints. Migration 3 adds a manual-retry generation with a
default of zero so existing installations upgrade without losing failed tasks;
Migration 4 adds nullable upload/transcription overrides to each recording
session. A `null` value inherits the Server Profile default, while an explicit
value is snapshotted before capture and remains stable across process restart,
retry, and manual stage actions.
unique WorkManager chains recover server-recorded parts, apply upload and
transcription network/charging constraints to separate stages, poll transcription
jobs, cache a validated immutable revision, and expose the cached text through
Compose. Manual upload and transcription policies stop at durable boundaries and
resume only from explicit row actions. The active Server Profile also selects a Room asset
Flow; the home screen renders its 50 most recently updated snapshots and reports
the full cached count without loading an unbounded Compose tree. This source path passes local SDK compilation, JVM,
lint, Room-schema, debug/test APK, and signed release APK/AAB gates, but it is
not device-accepted until the connected instrumentation and recovery E2E pass.
Cloud-provider secrets and backend business rules remain on VoiceAsset Server.

The active profile preference selects server-bound recording, transcript, and
remote-asset state. A null profile is valid: capture, playback, search, and
export remain local-only without authentication or network access. Profile
switching verifies the persisted ID and is disabled while capture or a recording
transition is active, preventing a live session from being visually reassigned
to another server.
At application startup, WorkManager recovery is resumed only for Profiles with
an available Keystore session; a saved but unconnected Profile therefore does
not cause an unsolicited network attempt.

Authentication state is profile-scoped and encrypted as one versioned Keystore
payload containing access and refresh credentials plus both expiries. A shared
`RefreshingServerSessionProvider` serializes the refresh boundary, rotates once
inside the five-minute access-expiry window, and replaces the complete payload.
Rejected/expired refresh or malformed storage removes the unusable local
session; access-only legacy payloads remain readable but cannot be rotated.
Neither token enters Compose state. `ApiPersonalDeviceSessions` performs an
explicit credential-free inventory read and, after a separate UI confirmation,
reads the inventory again before revoking the exact UUID. Current-device local
cleanup happens only after the remote DELETE succeeds; offline recordings and
profile metadata are retained. `AuthenticatedServerProfileReconnector` can then
replace the encrypted session for that exact Profile ID, preserving its offline
identity. Compose clears the attempted password before network I/O and never
persists it. Authentication failure does not mutate the existing session; a
post-authentication secure-storage failure removes any possibly partial local
replacement.

The same projection joins Room recording, sync-task, and transcript Flows. It
includes device-local rows with a null profile alongside rows for the selected
server, reports the full local count, and renders at most 50 recent entries with
capture state, upload progress, transcription availability, and durable error
details. Each row also shows its effective policies and identifies recording-level
overrides; this works offline and does not depend on the Server's incremental-sync
capability. Failed and blocked server-bound rows can be retried explicitly. A permanent failure
is reconstructed from its last asset/upload checkpoint; transcription retries
advance the persisted generation so they cannot replay a terminal job, while
stable asset and upload idempotency keys prevent duplicate remote objects.

One immutable query in ViewModel state filters both profile-scoped projections
entirely offline. Matching is case-insensitive across cached asset
title/ID/language/status/Collection identifiers and local recording
filename/ID/capture/sync/error fields. The state retains full and matching counts
separately, then applies the existing 50-row cap after filtering. Search does not
change the latest-recording transcript projection, and Compose renders standalone
controls when the active playback row falls outside the filtered result set.

Metadata editing remains a public-API operation rather than local-only state.
The editor loads the current asset and strong ETag, keeps that validator out of
Compose state, and performs one full title/language/Collection replacement with
the exact `If-Match`. A `409` or ambiguous transport failure closes the save
session and requires a fresh read. After success, Room replaces only the matching
profile/asset row while preserving its change sequence and cursor; subsequent
incremental pages apply resource versions monotonically so an older page cannot
undo the direct refresh.

Remote cache population is capability-selected. Servers advertising
`incremental_sync` retain the durable compare-and-set change cursor. Explicitly
compatible older servers restart from the stable asset-list cursor on each
bounded pull and only upsert present snapshots. This legacy bootstrap does not
advance the incremental checkpoint or infer deletion from absence; newer Room
versions and deletion tombstones always win. The same unique WorkManager path
runs after profile save, on profile selection, periodically, and through the
manual refresh action.

Mobile administration remains a separate, non-persistent control-plane
projection. `ApiMobileAdministration` resolves only the active persisted Profile
and its Keystore session, then reads the bounded administration job list,
workspace status, and credential-free ASR/LLM Profile lists through the typed
public API. Compose never receives provider config or session material. Profile
state changes send only `enabled` or `disabled` with the displayed version in an
exact `If-Match`; response ID, family, workspace, state, version, and ETag are
validated before immutable UI state advances. A conflict requires refresh, and
no SSH, shell, or arbitrary command boundary exists in the app. Provider health
is a separate explicit action because it may contact a vendor and records a
server-side check. The typed client validates profile identity, timestamp,
status/error semantics, and ASR-versus-LLM error-class families. Only that safe
classification is projected into transient UI state; raw vendor messages and
credentials are never retained.

Local playback and export share `RecordingFileVerifier`, which resolves a
terminal Room row and verifies its ID, flat supported filename, canonical
non-symlink path, byte length, and SHA-256 off the main thread. The playback
controller creates at most one MediaPlayer after verification, rejects stale
callbacks by generation, and models preparation, play, user pause, resume,
completion, failure, and stop as immutable UI state. It abandons audio focus on
pause/stop, responds to transient and permanent focus loss, and the Activity
pauses playback when an audio-becoming-noisy broadcast indicates output has
changed.

Export remains an explicit user action. `RecordingExporter` delegates file
resolution to the shared verifier and then builds an Android share intent. The non-exported
`RecordingFileProvider` accepts only a granted content URI for one flat M4A/WAV
file beneath `files/recordings/`, exposes read-only metadata and bytes, and
rejects writes or canonical paths outside that directory.

The in-progress real-time path uses `AudioRecord` for 16 kHz mono PCM, writes
every frame to a checkpointed local WAV before network delivery, and sends typed
protocol-v1 events through an authenticated OkHttp WebSocket. Bounded replay,
lost-ACK reconciliation, reconnect windows, sequence checks, and final-result
deduplication are JVM-tested. Network failure preserves the local archive;
cold-start recovery repairs valid WAV lengths and hands the saved recording to
the existing policy-aware WorkManager uploader. No real-time UI or foreground
service entry point is enabled until the Server upgrade adapter and device E2E
are complete.
