package com.voiceasset.core.api

const val SUPPORTED_API_VERSION = "v1"
const val SUPPORTED_CONTRACT_VERSION = "0.22.0"

private val compatibleContractVersions =
    setOf("0.13.0", "0.14.0", "0.15.0", "0.16.0", "0.17.0", "0.18.0", "0.19.0", "0.20.0", "0.21.0", SUPPORTED_CONTRACT_VERSION)

private val requiredAndroidSyncFeatures =
    setOf(
        "capability_negotiation",
        "m4a_uploads",
        "refresh_sessions",
        "resumable_uploads",
        "structured_errors",
        "transcription_jobs",
    )

fun ServerCapabilities.requireAndroidSyncCompatibility() {
    if (apiVersion != SUPPORTED_API_VERSION || contractVersion !in compatibleContractVersions) {
        throw VoiceAssetProtocolException(
            "Server API $apiVersion contract $contractVersion is not supported by this app.",
        )
    }
    val missing = requiredAndroidSyncFeatures - features.toSet()
    if (missing.isNotEmpty()) {
        throw VoiceAssetProtocolException(
            "Server is missing required Android sync capabilities: ${missing.sorted().joinToString()}.",
        )
    }
}

internal fun ServerCapabilities.requireDevicePairingCompatibility(payload: PairingPayload) {
    if (
        apiVersion != SUPPORTED_API_VERSION ||
        contractVersion != SUPPORTED_CONTRACT_VERSION ||
        contractVersion != payload.contractVersion ||
        "device_pairing" !in features
    ) {
        throw VoiceAssetProtocolException("Server does not support this device pairing payload.")
    }
}
