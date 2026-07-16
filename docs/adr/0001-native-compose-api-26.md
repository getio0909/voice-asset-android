# ADR 0001: Native Compose Client with API 26 Baseline

- Status: Accepted
- Date: 2026-07-16

## Decision

Use a native Kotlin, single-activity Jetpack Compose application with Material 3
and minimum API 26. Consume only the public VoiceAsset Server API and fail closed
when the recorded contract or required capabilities are incompatible.

## Consequences

API 26 covers modern foreground-service, TLS, and Keystore foundations while
retaining broad device support. Offline persistence, reliable work, and recording
are added behind explicit state machines in later vertical slices; the Phase 0
shell must not claim they exist.
