# Changelog

All notable changes to this project will be documented in this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases use [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial Kotlin and Jetpack Compose single-activity application.
- Material 3 startup screen with initialized and server-not-configured states.
- Unit, Compose UI test, lint, Gradle wrapper, and CI foundations.
- Ktlint formatting, emulator instrumentation CI, and a dependency-level
  CycloneDX SBOM with license-policy validation.
- Contract pin, architecture baseline, and API 26 decision record.

### Fixed

- Compile and target the current stable Android 16 SDK (API 36), matching the
  package available from the official stable SDK channel.
