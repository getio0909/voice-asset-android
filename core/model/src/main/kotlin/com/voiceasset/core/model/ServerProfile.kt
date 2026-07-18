package com.voiceasset.core.model

import java.net.URI
import java.util.Locale
import java.util.UUID

@JvmInline
value class ServerProfileId private constructor(
    val value: String,
) {
    companion object {
        fun parse(value: String): ServerProfileId = ServerProfileId(parseCanonicalUuid(value, "server profile id"))
    }
}

@JvmInline
value class ServerOrigin private constructor(
    val value: String,
) {
    companion object {
        fun parse(value: String): ServerOrigin {
            val candidate = value.trim()
            val uri =
                runCatching { URI(candidate) }
                    .getOrElse { throw IllegalArgumentException("server URL must be a valid URI", it) }

            require(uri.scheme.equals("https", ignoreCase = true)) {
                "server URL must use HTTPS"
            }
            require(!uri.isOpaque && uri.rawAuthority != null) {
                "server URL must be an HTTPS origin"
            }
            require(uri.rawUserInfo == null) { "server URL must not contain user information" }
            require(uri.rawQuery == null) { "server URL must not contain a query" }
            require(uri.rawFragment == null) { "server URL must not contain a fragment" }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "server URL must not contain a path"
            }

            val host = requireNotNull(uri.host) { "server URL must contain a valid host" }
            require(uri.port in -1..65_535 && uri.port != 0) {
                "server URL port must be between 1 and 65535"
            }

            val normalizedHost = host.removeSurrounding("[", "]").lowercase(Locale.ROOT)
            val renderedHost =
                if (':' in normalizedHost) {
                    "[$normalizedHost]"
                } else {
                    normalizedHost
                }
            val renderedPort =
                when (uri.port) {
                    -1, 443 -> ""
                    else -> ":${uri.port}"
                }

            return ServerOrigin("https://$renderedHost$renderedPort")
        }
    }
}

@JvmInline
value class CertificateFingerprint private constructor(
    val value: String,
) {
    companion object {
        private val compactSha256 = Regex("^[0-9a-fA-F]{64}$")
        private val delimitedSha256 = Regex("^(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}$")

        fun parse(value: String): CertificateFingerprint {
            val candidate = value.trim()
            require(compactSha256.matches(candidate) || delimitedSha256.matches(candidate)) {
                "certificate fingerprint must be a SHA-256 value"
            }

            return CertificateFingerprint(candidate.replace(":", "").lowercase(Locale.ROOT))
        }
    }
}

enum class AuthenticationMode {
    LOCAL_SESSION,
    API_TOKEN,
}

enum class UploadPolicy {
    MANUAL,
    ANY_NETWORK,
    WIFI_ONLY,
    CHARGING_AND_WIFI,
}

enum class TranscriptionPolicy {
    MANUAL,
    AFTER_UPLOAD,
    WIFI_ONLY,
    CHARGING_AND_WIFI,
    REALTIME,
    DISABLED,
}

@ConsistentCopyVisibility
data class ServerProfile private constructor(
    val id: ServerProfileId,
    val name: String,
    val origin: ServerOrigin,
    val authenticationMode: AuthenticationMode,
    val defaultUploadPolicy: UploadPolicy,
    val defaultTranscriptionPolicy: TranscriptionPolicy,
    val customCaPem: String?,
    val certificateFingerprint: CertificateFingerprint?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    companion object {
        private const val MAX_NAME_LENGTH = 100

        fun create(
            id: ServerProfileId,
            name: String,
            baseUrl: String,
            authenticationMode: AuthenticationMode,
            defaultUploadPolicy: UploadPolicy,
            defaultTranscriptionPolicy: TranscriptionPolicy,
            customCaPem: String?,
            certificateFingerprint: CertificateFingerprint?,
            createdAtEpochMillis: Long,
            updatedAtEpochMillis: Long,
        ): ServerProfile {
            val normalizedName = name.trim()
            require(normalizedName.isNotEmpty()) { "server profile name must not be blank" }
            require(normalizedName.length <= MAX_NAME_LENGTH) {
                "server profile name must not exceed $MAX_NAME_LENGTH characters"
            }
            require(normalizedName.none(Char::isISOControl)) {
                "server profile name must not contain control characters"
            }
            require(createdAtEpochMillis >= 0) { "created timestamp must not be negative" }
            require(updatedAtEpochMillis >= createdAtEpochMillis) {
                "updated timestamp must not precede created timestamp"
            }
            val normalizedCustomCa = customCaPem?.trim()?.takeIf(String::isNotEmpty)
            require(normalizedCustomCa == null || normalizedCustomCa.length <= MAX_CUSTOM_CA_LENGTH) {
                "custom CA PEM must not exceed $MAX_CUSTOM_CA_LENGTH characters"
            }
            require(
                normalizedCustomCa == null ||
                    (
                        normalizedCustomCa.startsWith("-----BEGIN CERTIFICATE-----") &&
                            normalizedCustomCa.endsWith("-----END CERTIFICATE-----")
                    ),
            ) {
                "custom CA must be PEM-encoded X.509 certificate data"
            }

            return ServerProfile(
                id = id,
                name = normalizedName,
                origin = ServerOrigin.parse(baseUrl),
                authenticationMode = authenticationMode,
                defaultUploadPolicy = defaultUploadPolicy,
                defaultTranscriptionPolicy = defaultTranscriptionPolicy,
                customCaPem = normalizedCustomCa,
                certificateFingerprint = certificateFingerprint,
                createdAtEpochMillis = createdAtEpochMillis,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        }

        private const val MAX_CUSTOM_CA_LENGTH = 65_536
    }
}

private val canonicalUuid =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

internal fun parseCanonicalUuid(
    value: String,
    label: String,
): String {
    val candidate = value.trim()
    require(canonicalUuid.matches(candidate)) { "$label must be a canonical UUID" }
    return runCatching { UUID.fromString(candidate).toString() }
        .getOrElse { throw IllegalArgumentException("$label must be a canonical UUID", it) }
}
