package com.voiceasset.core.api

import java.time.Instant

fun interface DevicePairingAuthenticator {
    fun authenticate(
        payload: String,
        deviceName: String,
    ): DevicePairingResult

    companion object {
        val UNAVAILABLE =
            DevicePairingAuthenticator { _, _ ->
                throw VoiceAssetProtocolException("Device pairing is unavailable.")
            }
    }
}

class ApiDevicePairingAuthenticator(
    private val apiFactory: (PairingPayload) -> VoiceAssetApiClient = VoiceAssetApiClient::forPairing,
    private val now: () -> Instant = Instant::now,
) : DevicePairingAuthenticator {
    override fun authenticate(
        payload: String,
        deviceName: String,
    ): DevicePairingResult {
        val parsed = PairingPayload.parse(payload, now())
        return DevicePairingResult(
            origin = parsed.origin,
            login = apiFactory(parsed).claimPairing(parsed, deviceName),
        )
    }
}

data class DevicePairingResult(
    val origin: String,
    val login: LoginResult,
) {
    override fun toString(): String = "DevicePairingResult(origin=$origin, login=[REDACTED])"
}
