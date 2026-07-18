package com.voiceasset.core.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object JsonCodec {
    private val format =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            encodeDefaults = false
        }

    fun encodeLoginRequest(request: LoginRequest): String = format.encodeToString(request)

    fun encodePairingClaimRequest(request: PairingClaimRequest): String = format.encodeToString(request)

    fun encodeChangePasswordRequest(request: ChangePasswordRequest): String = format.encodeToString(request)

    fun encodeCreateAssetRequest(request: CreateAssetRequest): String = format.encodeToString(request)

    fun encodeUpdateAssetMetadataRequest(request: UpdateAssetMetadataRequest): String = format.encodeToString(request)

    fun encodeUpdateProviderProfileStateRequest(request: UpdateProviderProfileStateRequest): String = format.encodeToString(request)

    fun encodeCreateUploadRequest(request: CreateUploadRequest): String = format.encodeToString(request)

    fun decodeCapabilities(value: String): ServerCapabilities = decode(value)

    fun decodeAdministrationJobList(value: String): AdministrationJobList = decode(value)

    fun decodeAdministrationJob(value: String): AdministrationJob = decode(value)

    fun decodeAdministrationSystemStatus(value: String): AdministrationSystemStatus = decode(value)

    fun decodeProviderProfile(value: String): ProviderProfile = decode(value)

    fun decodeProviderProfileList(value: String): ProviderProfileList = decode(value)

    fun decodeProviderHealth(value: String): ProviderHealth = decode(value)

    fun decodeWebSession(value: String): WebSession = decode(value)

    fun decodeDeviceSessionList(value: String): DeviceSessionList = decode(value)

    fun decodeAsset(value: String): Asset = decode(value)

    fun decodeAssetList(value: String): AssetList = decode(value)

    fun decodeSyncChangeList(value: String): SyncChangeList = decode(value)

    fun decodeUploadSession(value: String): UploadSession = decode(value)

    fun decodeUploadPart(value: String): UploadPart = decode(value)

    fun decodeTranscriptionJob(value: String): TranscriptionJob = decode(value)

    fun decodeTranscriptList(value: String): TranscriptList = decode(value)

    fun decodeTranscriptRevision(value: String): TranscriptRevision = decode(value)

    fun decodeError(value: String): ApiError? =
        try {
            format.decodeFromString<ErrorEnvelope>(value).error
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private inline fun <reified T> decode(value: String): T =
        try {
            format.decodeFromString(value)
        } catch (exception: SerializationException) {
            throw VoiceAssetProtocolException("Server response does not match the VoiceAsset contract.", exception)
        } catch (exception: IllegalArgumentException) {
            throw VoiceAssetProtocolException("Server response does not match the VoiceAsset contract.", exception)
        }
}
