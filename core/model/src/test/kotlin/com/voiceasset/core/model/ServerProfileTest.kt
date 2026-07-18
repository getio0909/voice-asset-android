package com.voiceasset.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerProfileTest {
    @Test
    fun createsNormalizedHttpsProfileWithoutCredentials() {
        val profile =
            ServerProfile.create(
                id = ServerProfileId.parse("5f6f7209-87e1-40e8-ad9b-23df239b6230"),
                name = "  Test Server  ",
                baseUrl = "https://API.GETIO.NET:10443/",
                authenticationMode = AuthenticationMode.LOCAL_SESSION,
                defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
                defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
                customCaPem = null,
                certificateFingerprint = null,
                createdAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            )

        assertEquals("Test Server", profile.name)
        assertEquals("https://api.getio.net:10443", profile.origin.value)
        assertEquals(AuthenticationMode.LOCAL_SESSION, profile.authenticationMode)
        assertEquals(UploadPolicy.WIFI_ONLY, profile.defaultUploadPolicy)
        assertEquals(TranscriptionPolicy.AFTER_UPLOAD, profile.defaultTranscriptionPolicy)
        assertNull(profile.customCaPem)
        assertNull(profile.certificateFingerprint)
    }

    @Test
    fun normalizesColonDelimitedCertificateFingerprint() {
        val fingerprint = CertificateFingerprint.parse(List(32) { "A9" }.joinToString(":"))

        assertEquals("a9".repeat(32), fingerprint.value)
    }

    @Test
    fun rejectsInsecureOrNonOriginServerUrls() {
        val invalidOrigins =
            listOf(
                "http://api.example.test",
                "https://user@example.test",
                "https://example.test/api",
                "https://example.test?workspace=one",
                "https://example.test/#fragment",
            )

        invalidOrigins.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerOrigin.parse(value)
            }
        }
    }

    @Test
    fun rejectsMalformedIdentifiersNamesFingerprintsAndTimestamps() {
        assertThrows(IllegalArgumentException::class.java) { ServerProfileId.parse("profile-1") }
        assertThrows(IllegalArgumentException::class.java) { CertificateFingerprint.parse("a9") }
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.create(
                id = ServerProfileId.parse("5f6f7209-87e1-40e8-ad9b-23df239b6230"),
                name = "\u0000bad",
                baseUrl = "https://example.test",
                authenticationMode = AuthenticationMode.API_TOKEN,
                defaultUploadPolicy = UploadPolicy.MANUAL,
                defaultTranscriptionPolicy = TranscriptionPolicy.MANUAL,
                customCaPem = null,
                certificateFingerprint = null,
                createdAtEpochMillis = 2_000,
                updatedAtEpochMillis = 1_000,
            )
        }
    }
}
