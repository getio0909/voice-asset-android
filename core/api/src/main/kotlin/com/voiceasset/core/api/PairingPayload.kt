package com.voiceasset.core.api

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class InvalidPairingPayloadException : IllegalArgumentException("Pairing payload is invalid or unavailable.")

class PairingSecret internal constructor(
    internal val value: String,
) {
    init {
        val encoded = value.removePrefix(PAIRING_SECRET_PREFIX)
        val decoded = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull()
        require(
            PAIRING_SECRET.matches(value) &&
                decoded?.size == PAIRING_SECRET_BYTES &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == encoded,
        ) { "pairing secret is invalid" }
    }

    override fun toString(): String = "PairingSecret([REDACTED])"
}

class PairingPayload internal constructor(
    val origin: String,
    val pairingSessionId: String,
    val secret: PairingSecret,
    val expiresAt: Instant,
    val contractVersion: String,
) {
    override fun toString(): String =
        "PairingPayload(origin=$origin, pairingSessionId=$pairingSessionId, " +
            "secret=[REDACTED], expiresAt=$expiresAt, contractVersion=$contractVersion)"

    companion object {
        @JvmStatic
        fun parse(
            value: String,
            now: Instant = Instant.now(),
        ): PairingPayload =
            try {
                parseStrict(value, now)
            } catch (_: Exception) {
                throw InvalidPairingPayloadException()
            }

        private fun parseStrict(
            value: String,
            now: Instant,
        ): PairingPayload {
            val normalized = value.trim()
            require(normalized.length in 1..MAX_PAIRING_PAYLOAD_LENGTH)
            val uri = URI(normalized)
            require(
                uri.scheme == PAIRING_SCHEME &&
                    uri.rawAuthority == PAIRING_HOST &&
                    uri.host == PAIRING_HOST &&
                    uri.userInfo == null &&
                    uri.port == -1 &&
                    uri.rawPath.isNullOrEmpty() &&
                    uri.rawFragment == null,
            )
            val fields = decodeQuery(requireNotNull(uri.rawQuery))
            require(fields.keys == REQUIRED_FIELDS)
            require(fields.getValue("version") == PAIRING_PAYLOAD_VERSION)
            require(fields.getValue("api_version") == SUPPORTED_API_VERSION)
            require(fields.getValue("contract_version") == SUPPORTED_CONTRACT_VERSION)
            val origin = validateOrigin(fields.getValue("origin"))
            val pairingSessionId = validateCanonicalUuid(fields.getValue("pairing_session_id"))
            val secret = PairingSecret(fields.getValue("secret"))
            val expiresAt = Instant.parse(fields.getValue("expires_at"))
            require(expiresAt.isAfter(now) && !expiresAt.isAfter(now.plus(MAX_PAIRING_FUTURE_TTL)))
            return PairingPayload(
                origin = origin,
                pairingSessionId = pairingSessionId,
                secret = secret,
                expiresAt = expiresAt,
                contractVersion = fields.getValue("contract_version"),
            )
        }

        private fun decodeQuery(rawQuery: String): Map<String, String> {
            val result = linkedMapOf<String, String>()
            rawQuery.split('&').forEach { pair ->
                val separator = pair.indexOf('=')
                require(separator > 0 && separator < pair.lastIndex && '+' !in pair)
                val name = decodeComponent(pair.substring(0, separator))
                val value = decodeComponent(pair.substring(separator + 1))
                require(name in REQUIRED_FIELDS && result.put(name, value) == null)
            }
            return result
        }

        private fun decodeComponent(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8).also { decoded ->
                require(decoded.isNotEmpty() && decoded.none(Char::isISOControl))
            }

        private fun validateOrigin(value: String): String {
            val origin = URI(value)
            require(
                origin.scheme == "https" &&
                    origin.host != null &&
                    origin.host == origin.host.lowercase() &&
                    origin.rawUserInfo == null &&
                    (origin.port == -1 || origin.port in 1..65535) &&
                    origin.rawPath.isNullOrEmpty() &&
                    origin.rawQuery == null &&
                    origin.rawFragment == null &&
                    origin.toASCIIString() == value,
            )
            return value
        }

        private fun validateCanonicalUuid(value: String): String {
            val parsed = UUID.fromString(value)
            require(parsed.toString() == value)
            return value
        }
    }
}

private const val PAIRING_SCHEME = "voiceasset"
private const val PAIRING_HOST = "pair"
private const val PAIRING_PAYLOAD_VERSION = "1"
private const val MAX_PAIRING_PAYLOAD_LENGTH = 2_048
private const val PAIRING_SECRET_PREFIX = "va_pair_"
private const val PAIRING_SECRET_BYTES = 32
private val MAX_PAIRING_FUTURE_TTL = Duration.ofMinutes(6)
private val PAIRING_SECRET = Regex("^va_pair_[A-Za-z0-9_-]{43}$")
private val REQUIRED_FIELDS =
    setOf(
        "api_version",
        "contract_version",
        "expires_at",
        "origin",
        "pairing_session_id",
        "secret",
        "version",
    )
