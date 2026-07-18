# Android Release Signing

The repository uses one long-lived RSA upload key for direct APK distribution
and Google Play App Signing. The keystore stays outside Git, and CI receives it
only through these GitHub Actions Secrets in `getio0909/voice-asset-android`:

- `ANDROID_KEYSTORE_BASE64` — base64 of the PKCS#12 keystore
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — `voiceasset-upload`
- `ANDROID_KEY_PASSWORD` — key password

The checked-in Gradle build signs `release` only when all four local variables
are present: `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Without them, a release build
must not be treated as a distributable artifact.

For local builds, set those variables from a secure secret store and run:

```bash
./gradlew test ktlintCheck lintRelease assembleRelease bundleRelease \
  :app:cyclonedxDirectBom
bash scripts/package-release.sh v0.1.0 dist-release
bash scripts/verify-release.sh v0.1.0 dist-release
```

`verify-release.sh` checks package metadata, APK v1/v2/v3 signatures, AAB
signature metadata, the SBOM, and SHA-256 checksums. The tag workflow performs
the same checks and uploads a draft prerelease. Physical-device installation,
upgrade, and recovery remain required before a public release.

Never commit a keystore, password, `local.properties`, or signed artifacts.
Keep an offline backup of the keystore and its password; losing either makes
future updates incompatible with existing installations.
