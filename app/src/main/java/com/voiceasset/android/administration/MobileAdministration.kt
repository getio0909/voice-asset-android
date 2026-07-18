package com.voiceasset.android.administration

import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.android.security.RefreshingServerSessionProvider
import com.voiceasset.android.security.ServerCredentialStore
import com.voiceasset.android.security.ServerSessionAuthenticationRequiredException
import com.voiceasset.core.api.AdministrationJob
import com.voiceasset.core.api.AdministrationSystemStatus
import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.ProviderHealth
import com.voiceasset.core.api.ProviderProfile
import com.voiceasset.core.api.ProviderProfileState
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId

enum class ProviderProfileFamily {
    ASR,
    LLM,
}

data class MobileAdministrationProviderProfile(
    val family: ProviderProfileFamily,
    val profile: ProviderProfile,
    val health: ProviderHealth? = null,
)

data class MobileAdministrationSnapshot(
    val serverProfileId: ServerProfileId,
    val systemStatus: AdministrationSystemStatus,
    val jobs: List<AdministrationJob>,
    val providers: List<MobileAdministrationProviderProfile>,
)

interface MobileAdministration {
    suspend fun load(serverProfileId: ServerProfileId): MobileAdministrationSnapshot

    suspend fun retryJob(
        serverProfileId: ServerProfileId,
        jobId: String,
    ): AdministrationJob

    suspend fun setProviderProfileState(
        serverProfileId: ServerProfileId,
        family: ProviderProfileFamily,
        providerProfileId: String,
        expectedVersion: Long,
        state: ProviderProfileState,
    ): MobileAdministrationProviderProfile

    suspend fun checkProviderProfileHealth(
        serverProfileId: ServerProfileId,
        family: ProviderProfileFamily,
        providerProfileId: String,
    ): ProviderHealth

    companion object {
        val NONE =
            object : MobileAdministration {
                override suspend fun load(serverProfileId: ServerProfileId): MobileAdministrationSnapshot =
                    throw MobileAdministrationUnavailableException()

                override suspend fun retryJob(
                    serverProfileId: ServerProfileId,
                    jobId: String,
                ): AdministrationJob = throw MobileAdministrationUnavailableException()

                override suspend fun setProviderProfileState(
                    serverProfileId: ServerProfileId,
                    family: ProviderProfileFamily,
                    providerProfileId: String,
                    expectedVersion: Long,
                    state: ProviderProfileState,
                ): MobileAdministrationProviderProfile = throw MobileAdministrationUnavailableException()

                override suspend fun checkProviderProfileHealth(
                    serverProfileId: ServerProfileId,
                    family: ProviderProfileFamily,
                    providerProfileId: String,
                ): ProviderHealth = throw MobileAdministrationUnavailableException()
            }
    }
}

class ApiMobileAdministration(
    private val profiles: ServerProfileRepository,
    private val sessions: RefreshingServerSessionProvider,
) : MobileAdministration {
    constructor(
        profiles: ServerProfileRepository,
        credentials: ServerCredentialStore,
        apiFactory: (ServerProfile, BearerCredential?) -> VoiceAssetApi,
    ) : this(
        profiles = profiles,
        sessions = RefreshingServerSessionProvider(credentials, apiFactory),
    )

    override suspend fun load(serverProfileId: ServerProfileId): MobileAdministrationSnapshot {
        val api = createApi(serverProfileId)
        val systemStatus = api.getAdministrationSystemStatus()
        val jobs = api.listAdministrationJobs(limit = MAX_MOBILE_JOBS).items
        val asrProfiles =
            api.listAsrProviderProfiles().items.map { profile ->
                MobileAdministrationProviderProfile(ProviderProfileFamily.ASR, profile)
            }
        val llmProfiles =
            api.listLlmProviderProfiles().items.map { profile ->
                MobileAdministrationProviderProfile(ProviderProfileFamily.LLM, profile)
            }
        return MobileAdministrationSnapshot(
            serverProfileId = serverProfileId,
            systemStatus = systemStatus,
            jobs = jobs,
            providers = asrProfiles + llmProfiles,
        )
    }

    override suspend fun retryJob(
        serverProfileId: ServerProfileId,
        jobId: String,
    ): AdministrationJob = createApi(serverProfileId).retryAdministrationJob(jobId)

    override suspend fun setProviderProfileState(
        serverProfileId: ServerProfileId,
        family: ProviderProfileFamily,
        providerProfileId: String,
        expectedVersion: Long,
        state: ProviderProfileState,
    ): MobileAdministrationProviderProfile {
        val api = createApi(serverProfileId)
        val updated =
            when (family) {
                ProviderProfileFamily.ASR ->
                    api.updateAsrProviderProfileState(
                        profileId = providerProfileId,
                        expectedVersion = expectedVersion,
                        state = state,
                    )
                ProviderProfileFamily.LLM ->
                    api.updateLlmProviderProfileState(
                        profileId = providerProfileId,
                        expectedVersion = expectedVersion,
                        state = state,
                    )
            }
        return MobileAdministrationProviderProfile(family, updated.profile)
    }

    override suspend fun checkProviderProfileHealth(
        serverProfileId: ServerProfileId,
        family: ProviderProfileFamily,
        providerProfileId: String,
    ): ProviderHealth {
        val api = createApi(serverProfileId)
        return when (family) {
            ProviderProfileFamily.ASR -> api.checkAsrProviderProfileHealth(providerProfileId)
            ProviderProfileFamily.LLM -> api.checkLlmProviderProfileHealth(providerProfileId)
        }
    }

    private suspend fun createApi(serverProfileId: ServerProfileId): VoiceAssetApi {
        val profile = profiles.find(serverProfileId) ?: throw MobileAdministrationProfileUnavailableException()
        return try {
            sessions.createApi(profile)
        } catch (exception: ServerSessionAuthenticationRequiredException) {
            throw MobileAdministrationAuthenticationRequiredException(exception)
        }
    }
}

class MobileAdministrationUnavailableException : IllegalStateException("mobile administration is unavailable")

class MobileAdministrationProfileUnavailableException : IllegalStateException("server profile is unavailable")

class MobileAdministrationAuthenticationRequiredException(
    cause: Throwable? = null,
) : IllegalStateException("server authentication is required", cause)

private const val MAX_MOBILE_JOBS = 20
