# Security Policy

## Supported versions

Security fixes are applied to the latest development branch until the first stable release. A version support table will be published with `v1.0.0`.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use GitHub's private vulnerability reporting feature for this repository. Include affected versions, reproduction steps, impact, and any suggested mitigation. Maintainers will acknowledge a complete report within seven days and coordinate disclosure after a fix is available.

Do not include production tokens, private recordings, signing material, or other personal data in a report. Use synthetic fixtures and redact logs.

## Android security baseline

The app must never store ASR or LLM provider secrets. Server tokens must be protected by Android Keystore, logs must be redacted, and TLS verification must not have a permanent bypass. Release signing keys belong in an external secret store and must never enter this repository.
