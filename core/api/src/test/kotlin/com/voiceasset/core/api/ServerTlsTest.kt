package com.voiceasset.core.api

import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.CertificateFingerprint
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class ServerTlsTest {
    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate

    @Before
    fun startServer() {
        certificate =
            HeldCertificate
                .Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `custom CA plus matching certificate fingerprint permits HTTPS`() {
        server.enqueue(capabilitiesResponse())
        val client = VoiceAssetApiClient.forProfile(profile(fingerprint()), credential = null)

        val capabilities = client.getCapabilities()

        assertEquals("0.22.0", capabilities.contractVersion)
    }

    @Test
    fun `custom CA without a certificate fingerprint permits HTTPS`() {
        server.enqueue(capabilitiesResponse())
        val client = VoiceAssetApiClient.forProfile(profile(fingerprint = null), credential = null)

        val capabilities = client.getCapabilities()

        assertEquals("0.22.0", capabilities.contractVersion)
    }

    @Test
    fun `trusted custom CA still rejects a mismatched certificate fingerprint`() {
        server.enqueue(capabilitiesResponse())
        val client =
            VoiceAssetApiClient.forProfile(
                profile(CertificateFingerprint.parse("00".repeat(32))),
                credential = null,
            )

        assertThrows(VoiceAssetTlsException::class.java) { client.getCapabilities() }
    }

    @Test
    fun `matching certificate fingerprint does not bypass chain validation`() {
        server.enqueue(capabilitiesResponse())
        val client =
            VoiceAssetApiClient.forProfile(
                profile(
                    fingerprint = fingerprint(),
                    customCaPem = null,
                ),
                credential = null,
            )

        assertThrows(VoiceAssetTlsException::class.java) { client.getCapabilities() }
    }

    private fun profile(
        fingerprint: CertificateFingerprint?,
        customCaPem: String? = certificatePem(),
    ): ServerProfile =
        ServerProfile.create(
            id = ServerProfileId.parse("5f6f7209-87e1-40e8-ad9b-23df239b6230"),
            name = "TLS test",
            baseUrl = "https://localhost:${server.port}",
            authenticationMode = AuthenticationMode.API_TOKEN,
            defaultUploadPolicy = UploadPolicy.MANUAL,
            defaultTranscriptionPolicy = TranscriptionPolicy.MANUAL,
            customCaPem = customCaPem,
            certificateFingerprint = fingerprint,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )

    private fun fingerprint(): CertificateFingerprint {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded)
        return CertificateFingerprint.parse(digest.joinToString("") { byte -> "%02x".format(byte) })
    }

    private fun certificatePem(): String {
        val encoded =
            Base64
                .getMimeEncoder(64, "\n".toByteArray())
                .encodeToString(certificate.certificate.encoded)
        return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----"
    }
}

private fun capabilitiesResponse(): MockResponse =
    MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {
              "server_version":"0.1.0-dev",
              "api_version":"v1",
              "contract_version":"0.22.0",
              "features":["capability_negotiation"]
            }
            """.trimIndent(),
        )
