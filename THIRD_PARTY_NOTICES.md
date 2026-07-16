# Third-Party Dependencies

VoiceAsset Android uses AndroidX, Jetpack Compose, Material 3, Kotlin, JUnit,
and AndroidX Test components. Versions are pinned in `gradle/libs.versions.toml`.

CI resolves the release runtime graph with the CycloneDX Gradle plugin, emits
`app/build/reports/cyclonedx-direct/bom.json`, verifies representative runtime components
are present, and rejects missing or unreviewed license metadata. The generated
SBOM is the authoritative dependency inventory for each build.
