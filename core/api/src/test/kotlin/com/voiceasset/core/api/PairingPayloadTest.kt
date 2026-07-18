package com.voiceasset.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class PairingPayloadTest {
    @Test
    fun `strict parser accepts the canonical versioned payload and redacts its secret`() {
        val parsed = PairingPayload.parse(validPayload(), NOW)

        assertEquals("https://api.example.com:10443", parsed.origin)
        assertEquals(PAIRING_ID, parsed.pairingSessionId)
        assertEquals("0.22.0", parsed.contractVersion)
        assertEquals(EXPIRES_AT, parsed.expiresAt)
        assertEquals(SECRET, parsed.secret.value)
        assertFalse(parsed.toString().contains(SECRET))
        assertFalse(parsed.secret.toString().contains(SECRET))
    }

    @Test
    fun `strict parser rejects ambiguous downgraded expired and non TLS payloads`() {
        val invalidPayloads =
            listOf(
                validPayload() + "&secret=${encode(SECRET)}",
                validPayload(extra = "surprise" to "true"),
                validPayload(scheme = "VOICEASSET"),
                validPayload(host = "other"),
                validPayload(path = "/extra"),
                validPayload(fragment = "fragment"),
                validPayload(overrides = mapOf("version" to "2")),
                validPayload(overrides = mapOf("api_version" to "v2")),
                validPayload(overrides = mapOf("contract_version" to "0.18.0")),
                validPayload(overrides = mapOf("origin" to "http://api.example.com:10443")),
                validPayload(overrides = mapOf("origin" to "https://api.example.com:10443/")),
                validPayload(overrides = mapOf("pairing_session_id" to "not-a-uuid")),
                validPayload(overrides = mapOf("secret" to "va_pair_${"A".repeat(42)}")),
                validPayload(overrides = mapOf("expires_at" to "2026-07-18T03:59:59Z")),
                validPayload(overrides = mapOf("expires_at" to "2026-07-18T04:06:01Z")),
            )

        invalidPayloads.forEach { value ->
            val exception =
                assertThrows(InvalidPairingPayloadException::class.java) {
                    PairingPayload.parse(value, NOW)
                }
            assertFalse(exception.toString().contains(SECRET))
        }
    }

    @Test
    fun `strict parser rejects malformed percent encoding without echoing input`() {
        val malformed = validPayload().replace("origin=https", "origin=%ZZhttps")

        val exception =
            assertThrows(InvalidPairingPayloadException::class.java) {
                PairingPayload.parse(malformed, NOW)
            }

        assertFalse(exception.toString().contains("%ZZ"))
    }

    private fun validPayload(
        overrides: Map<String, String> = emptyMap(),
        extra: Pair<String, String>? = null,
        scheme: String = "voiceasset",
        host: String = "pair",
        path: String = "",
        fragment: String? = null,
    ): String {
        val fields =
            linkedMapOf(
                "api_version" to "v1",
                "contract_version" to "0.22.0",
                "expires_at" to EXPIRES_AT.toString(),
                "origin" to "https://api.example.com:10443",
                "pairing_session_id" to PAIRING_ID,
                "secret" to SECRET,
                "version" to "1",
            ).apply {
                putAll(overrides)
                extra?.let { put(it.first, it.second) }
            }
        val query = fields.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return "$scheme://$host$path?$query${fragment?.let { "#$it" }.orEmpty()}"
    }

    private fun encode(value: String): String =
        URLEncoder
            .encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-18T04:00:00Z")
        val EXPIRES_AT: Instant = Instant.parse("2026-07-18T04:05:00Z")
        const val PAIRING_ID = "40000000-0000-4000-8000-000000000001"
        const val SECRET = "va_pair_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
