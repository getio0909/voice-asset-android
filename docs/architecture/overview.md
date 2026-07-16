# Android Architecture Baseline

The Phase 0 app is a single-activity Jetpack Compose application targeting API
26 and Server contract `0.1.0`. UI state is immutable and deliberately reports
that no Server is configured instead of simulating product behavior.

Phase 2 will add server profiles, Room-backed offline assets, WorkManager upload
recovery, Keystore-protected credentials, and foreground recording as tested
vertical slices. Cloud-provider secrets and backend business rules remain on
VoiceAsset Server.
