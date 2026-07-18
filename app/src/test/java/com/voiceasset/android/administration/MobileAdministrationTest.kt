package com.voiceasset.android.administration

import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.android.security.ServerCredentialStore
import com.voiceasset.core.api.AdministrationAssetStatus
import com.voiceasset.core.api.AdministrationJob
import com.voiceasset.core.api.AdministrationJobList
import com.voiceasset.core.api.AdministrationJobStatus
import com.voiceasset.core.api.AdministrationProviderStatus
import com.voiceasset.core.api.AdministrationStorageStatus
import com.voiceasset.core.api.AdministrationSystemStatus
import com.voiceasset.core.api.AdministrationTranscriptStatus
import com.voiceasset.core.api.ProviderHealth
import com.voiceasset.core.api.ProviderHealthErrorClass
import com.voiceasset.core.api.ProviderHealthStatus
import com.voiceasset.core.api.ProviderProfile
import com.voiceasset.core.api.ProviderProfileList
import com.voiceasset.core.api.ProviderProfileState
import com.voiceasset.core.api.VersionedProviderProfile
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class MobileAdministrationTest {
    @Test
    fun `load returns bounded jobs and credential-free provider profiles`() =
        runBlocking {
            val profile = profile()
            val credentials = FakeCredentialStore(TOKEN.encodeToByteArray())
            val api = RecordingAdministrationApi()
            val factoryCredentials = mutableListOf<String>()
            val administration =
                ApiMobileAdministration(
                    profiles = FakeServerProfileRepository(profile),
                    credentials = credentials,
                    apiFactory = { _, credential ->
                        factoryCredentials += requireNotNull(credential).value
                        api.proxy
                    },
                )

            val snapshot = administration.load(profile.id)

            assertEquals(profile.id, snapshot.serverProfileId)
            assertEquals(4L, snapshot.systemStatus.assets.total)
            assertEquals(listOf(JOB_ID), snapshot.jobs.map(AdministrationJob::id))
            assertEquals(
                listOf(ProviderProfileFamily.ASR, ProviderProfileFamily.LLM),
                snapshot.providers.map(MobileAdministrationProviderProfile::family),
            )
            assertEquals(listOf(20), api.jobLimits)
            assertEquals(listOf(TOKEN), factoryCredentials)
            assertTrue(credentials.returnedBuffers.single().all { byte -> byte == 0.toByte() })
        }

    @Test
    fun `profile state update selects the exact provider family and version`() =
        runBlocking {
            val profile = profile()
            val api = RecordingAdministrationApi()
            val administration =
                ApiMobileAdministration(
                    profiles = FakeServerProfileRepository(profile),
                    credentials = FakeCredentialStore(TOKEN.encodeToByteArray()),
                    apiFactory = { _, _ -> api.proxy },
                )

            val updated =
                administration.setProviderProfileState(
                    serverProfileId = profile.id,
                    family = ProviderProfileFamily.LLM,
                    providerProfileId = LLM_PROFILE_ID,
                    expectedVersion = 1,
                    state = ProviderProfileState.ENABLED,
                )

            assertEquals(ProviderProfileFamily.LLM, updated.family)
            assertEquals(ProviderProfileState.ENABLED, updated.profile.state)
            assertEquals(listOf(ProviderUpdate(LLM_PROFILE_ID, 1, ProviderProfileState.ENABLED)), api.llmUpdates)
            assertEquals(emptyList<ProviderUpdate>(), api.asrUpdates)
        }

    @Test
    fun `provider health check selects the exact family and returns only safe classification`() =
        runBlocking {
            val profile = profile()
            val api = RecordingAdministrationApi()
            val administration =
                ApiMobileAdministration(
                    profiles = FakeServerProfileRepository(profile),
                    credentials = FakeCredentialStore(TOKEN.encodeToByteArray()),
                    apiFactory = { _, _ -> api.proxy },
                )

            val asr =
                administration.checkProviderProfileHealth(
                    serverProfileId = profile.id,
                    family = ProviderProfileFamily.ASR,
                    providerProfileId = ASR_PROFILE_ID,
                )
            val llm =
                administration.checkProviderProfileHealth(
                    serverProfileId = profile.id,
                    family = ProviderProfileFamily.LLM,
                    providerProfileId = LLM_PROFILE_ID,
                )

            assertEquals(ProviderHealthStatus.HEALTHY, asr.status)
            assertEquals(ProviderHealthStatus.UNHEALTHY, llm.status)
            assertEquals(ProviderHealthErrorClass.AUTHENTICATION, llm.errorClass)
            assertEquals(listOf(ASR_PROFILE_ID), api.asrHealthChecks)
            assertEquals(listOf(LLM_PROFILE_ID), api.llmHealthChecks)
        }

    @Test
    fun `job retry uses the exact authenticated server profile and job`() =
        runBlocking {
            val profile = profile()
            val api = RecordingAdministrationApi()
            val administration =
                ApiMobileAdministration(
                    profiles = FakeServerProfileRepository(profile),
                    credentials = FakeCredentialStore(TOKEN.encodeToByteArray()),
                    apiFactory = { _, _ -> api.proxy },
                )

            val retried = administration.retryJob(profile.id, JOB_ID)

            assertEquals(JOB_ID, retried.id)
            assertEquals("queued", retried.state)
            assertEquals(4, retried.maxAttempts)
            assertEquals(listOf(JOB_ID), api.jobRetries)
        }

    @Test
    fun `missing profile or credential fails before API construction`() {
        val profile = profile()
        var factoryCalls = 0
        val noProfile =
            ApiMobileAdministration(
                profiles = FakeServerProfileRepository(),
                credentials = FakeCredentialStore(TOKEN.encodeToByteArray()),
                apiFactory = { _, _ ->
                    factoryCalls += 1
                    RecordingAdministrationApi().proxy
                },
            )
        assertThrows(MobileAdministrationProfileUnavailableException::class.java) {
            runBlocking { noProfile.load(profile.id) }
        }
        val noCredential =
            ApiMobileAdministration(
                profiles = FakeServerProfileRepository(profile),
                credentials = FakeCredentialStore(null),
                apiFactory = { _, _ ->
                    factoryCalls += 1
                    RecordingAdministrationApi().proxy
                },
            )
        assertThrows(MobileAdministrationAuthenticationRequiredException::class.java) {
            runBlocking { noCredential.load(profile.id) }
        }
        assertEquals(0, factoryCalls)
    }

    private class RecordingAdministrationApi {
        val jobLimits = mutableListOf<Int>()
        val asrUpdates = mutableListOf<ProviderUpdate>()
        val llmUpdates = mutableListOf<ProviderUpdate>()
        val asrHealthChecks = mutableListOf<String>()
        val llmHealthChecks = mutableListOf<String>()
        val jobRetries = mutableListOf<String>()
        val proxy: VoiceAssetApi =
            Proxy.newProxyInstance(
                VoiceAssetApi::class.java.classLoader,
                arrayOf(VoiceAssetApi::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "getAdministrationSystemStatus" -> systemStatus()
                    "listAdministrationJobs" -> {
                        jobLimits += arguments?.get(1) as Int
                        AdministrationJobList(listOf(job()))
                    }
                    "retryAdministrationJob" -> {
                        val jobId = arguments?.get(0) as String
                        jobRetries += jobId
                        job().copy(state = "queued", maxAttempts = 4, retryable = false, lastErrorCode = null)
                    }
                    "listAsrProviderProfiles" -> ProviderProfileList(listOf(provider(ASR_PROFILE_ID, "mock_asr")))
                    "listLlmProviderProfiles" -> ProviderProfileList(listOf(provider(LLM_PROFILE_ID, "mock_llm")))
                    "updateAsrProviderProfileState" -> {
                        val update =
                            ProviderUpdate(
                                arguments?.get(0) as String,
                                arguments[1] as Long,
                                arguments[2] as ProviderProfileState,
                            )
                        asrUpdates += update
                        VersionedProviderProfile(
                            provider(update.id, "mock_asr", update.state, update.version + 1),
                            "\"${update.version + 1}\"",
                        )
                    }
                    "updateLlmProviderProfileState" -> {
                        val update =
                            ProviderUpdate(
                                arguments?.get(0) as String,
                                arguments[1] as Long,
                                arguments[2] as ProviderProfileState,
                            )
                        llmUpdates += update
                        VersionedProviderProfile(
                            provider(update.id, "mock_llm", update.state, update.version + 1),
                            "\"${update.version + 1}\"",
                        )
                    }
                    "checkAsrProviderProfileHealth" -> {
                        val profileId = arguments?.get(0) as String
                        asrHealthChecks += profileId
                        providerHealth(profileId, ProviderHealthStatus.HEALTHY)
                    }
                    "checkLlmProviderProfileHealth" -> {
                        val profileId = arguments?.get(0) as String
                        llmHealthChecks += profileId
                        providerHealth(
                            profileId,
                            ProviderHealthStatus.UNHEALTHY,
                            ProviderHealthErrorClass.AUTHENTICATION,
                        )
                    }
                    "toString" -> "RecordingAdministrationApi"
                    else -> error("unexpected VoiceAssetApi call: ${method.name}")
                }
            } as VoiceAssetApi
    }

    private class FakeServerProfileRepository(
        vararg profiles: ServerProfile,
    ) : ServerProfileRepository {
        private val values = MutableStateFlow(profiles.toList())

        override fun observeAll(): Flow<List<ServerProfile>> = values

        override suspend fun find(id: ServerProfileId): ServerProfile? = values.value.firstOrNull { it.id == id }

        override suspend fun save(profile: ServerProfile) = Unit

        override suspend fun delete(id: ServerProfileId) = Unit
    }

    private class FakeCredentialStore(
        private val value: ByteArray?,
    ) : ServerCredentialStore {
        val returnedBuffers = mutableListOf<ByteArray>()

        override suspend fun write(
            profileId: ServerProfileId,
            credential: ByteArray,
        ) = Unit

        override suspend fun read(profileId: ServerProfileId): ByteArray? = value?.copyOf()?.also(returnedBuffers::add)

        override suspend fun remove(profileId: ServerProfileId) = Unit
    }

    private data class ProviderUpdate(
        val id: String,
        val version: Long,
        val state: ProviderProfileState,
    )

    companion object {
        const val TOKEN = "va_test_session_1234567890"
        const val JOB_ID = "30000000-0000-4000-8000-000000000003"
        const val ASR_PROFILE_ID = "31000000-0000-4000-8000-000000000003"
        const val LLM_PROFILE_ID = "32000000-0000-4000-8000-000000000003"
    }
}

private fun profile(): ServerProfile =
    ServerProfile.create(
        id = ServerProfileId.parse("5f6f7209-87e1-40e8-ad9b-23df239b6230"),
        name = "Test server",
        baseUrl = "https://example.test",
        authenticationMode = AuthenticationMode.LOCAL_SESSION,
        defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
        defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
        customCaPem = null,
        certificateFingerprint = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

private fun job(): AdministrationJob =
    AdministrationJob(
        id = MobileAdministrationTest.JOB_ID,
        assetId = null,
        createdBy = "10000000-0000-4000-8000-000000000001",
        kind = "mock_transcribe",
        state = "failed",
        attempts = 3,
        maxAttempts = 3,
        retryable = true,
        lastErrorCode = "provider_unavailable",
        availableAt = "2026-07-16T08:00:00Z",
        createdAt = "2026-07-16T08:00:00Z",
        updatedAt = "2026-07-16T08:01:00Z",
    )

private fun systemStatus(): AdministrationSystemStatus =
    AdministrationSystemStatus(
        generatedAt = "2026-07-16T08:02:00Z",
        activeUsers = 2,
        assets = AdministrationAssetStatus(4, 3, 1, 0, 0, 65_000),
        storage = AdministrationStorageStatus(3, 2_048),
        transcripts = AdministrationTranscriptStatus(2, 3),
        jobs = AdministrationJobStatus(1, 0, 0, 0, 0, 1, 0),
        providers = AdministrationProviderStatus(1, 1),
    )

private fun provider(
    id: String,
    providerId: String,
    state: ProviderProfileState = ProviderProfileState.DISABLED,
    version: Long = 1,
): ProviderProfile =
    ProviderProfile(
        id = id,
        workspaceId = "20000000-0000-4000-8000-000000000002",
        providerId = providerId,
        displayName = "Mock provider",
        config = buildJsonObject { put("model", "mock-v1") },
        state = state,
        priority = 100,
        version = version,
        secretConfigured = true,
        createdAt = "2026-07-16T08:00:00Z",
        updatedAt = "2026-07-16T08:01:00Z",
    )

private fun providerHealth(
    profileId: String,
    status: ProviderHealthStatus,
    errorClass: ProviderHealthErrorClass? = null,
): ProviderHealth =
    ProviderHealth(
        profileId = profileId,
        status = status,
        errorClass = errorClass,
        checkedAt = "2026-07-16T08:03:00Z",
    )
