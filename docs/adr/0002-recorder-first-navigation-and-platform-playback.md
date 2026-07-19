# ADR 0002: Recorder-First Navigation and Platform Playback

- Status: Accepted
- Date: 2026-07-19

## Context

The Android client is a local recorder before it is a server administration
client. A recorder workflow needs immediate access to local recordings,
search, filters, sorting, and one obvious record action. Playback must also
remain reliable across API 26+ devices instead of assuming a vendor codec.

## Decision

Use one top app bar with three explicit states: local recordings, recording,
and settings. Keep server, login, and synchronization controls secondary to
the local library. Keep language selection inside Settings. Each recording row
has one stateful play/pause/stop control; playback is verified by the shared
file verifier before a single platform `MediaPlayer` is created.

Expose system-default, hardware-preferred, and compatibility playback choices
as a user preference. Hardware preference is best-effort: Android's platform
player owns codec selection and must safely fall back when no hardware decoder
is exposed. Direct `MediaCodec` plus `AudioTrack` playback is not introduced
until a measured device requirement justifies its lifecycle and power cost.

## Consequences

The home screen matches familiar recorder interaction patterns without adding
a competing drawer and bottom navigation. The decoder setting is portable and
cannot make a playable file fail solely because a device lacks a hardware
codec, but it does not promise manual codec selection. Physical-device audio,
focus, and decoder behavior remain acceptance evidence for the next connected
device session.
