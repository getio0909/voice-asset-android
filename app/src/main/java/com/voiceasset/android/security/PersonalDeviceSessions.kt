package com.voiceasset.android.security

import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.DeviceSession
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId

data class PersonalDeviceSessionsSnapshot(
    val serverProfileId: ServerProfileId,
    val items: List<DeviceSession>,
)

interface PersonalDeviceSessions {
    suspend fun load(serverProfileId: ServerProfileId): PersonalDeviceSessionsSnapshot

    suspend fun revoke(
        serverProfileId: ServerProfileId,
        deviceSessionId: String,
    ): DeviceSession

    companion object {
        val NONE =
            object : PersonalDeviceSessions {
                override suspend fun load(serverProfileId: ServerProfileId): PersonalDeviceSessionsSnapshot =
                    throw PersonalDeviceSessionsUnavailableException()

                override suspend fun revoke(
                    serverProfileId: ServerProfileId,
                    deviceSessionId: String,
                ): DeviceSession = throw PersonalDeviceSessionsUnavailableException()
            }
    }
}

class ApiPersonalDeviceSessions(
    private val profiles: ServerProfileRepository,
    private val sessions: RefreshingServerSessionProvider,
) : PersonalDeviceSessions {
    constructor(
        profiles: ServerProfileRepository,
        credentials: ServerCredentialStore,
        apiFactory: (ServerProfile, BearerCredential?) -> VoiceAssetApi,
    ) : this(
        profiles = profiles,
        sessions = RefreshingServerSessionProvider(credentials, apiFactory),
    )

    override suspend fun load(serverProfileId: ServerProfileId): PersonalDeviceSessionsSnapshot {
        val api = createApi(serverProfileId)
        return PersonalDeviceSessionsSnapshot(
            serverProfileId = serverProfileId,
            items = api.listDeviceSessions().items,
        )
    }

    override suspend fun revoke(
        serverProfileId: ServerProfileId,
        deviceSessionId: String,
    ): DeviceSession {
        val api = createApi(serverProfileId)
        val target =
            api.listDeviceSessions().items.firstOrNull { item -> item.id == deviceSessionId }
                ?: throw PersonalDeviceSessionNotFoundException()
        api.revokeDeviceSession(target.id)
        if (target.current) {
            sessions.remove(serverProfileId)
        }
        return target
    }

    private suspend fun createApi(serverProfileId: ServerProfileId): VoiceAssetApi {
        val profile = profiles.find(serverProfileId) ?: throw PersonalDeviceSessionsProfileUnavailableException()
        return try {
            sessions.createApi(profile)
        } catch (exception: ServerSessionAuthenticationRequiredException) {
            throw PersonalDeviceSessionsAuthenticationRequiredException(exception)
        }
    }
}

class PersonalDeviceSessionsUnavailableException : IllegalStateException("personal device sessions are unavailable")

class PersonalDeviceSessionsProfileUnavailableException : IllegalStateException("server profile is unavailable")

class PersonalDeviceSessionsAuthenticationRequiredException(
    cause: Throwable? = null,
) : IllegalStateException("server authentication is required", cause)

class PersonalDeviceSessionNotFoundException : IllegalStateException("device session is unavailable")
