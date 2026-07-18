package com.voiceasset.android.security

import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.DeviceSession
import com.voiceasset.core.api.DeviceSessionList
import com.voiceasset.core.api.RefreshCredential
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

class PersonalDeviceSessionsTest {
    @Test
    fun `load returns the inventory for the exact authenticated profile`() =
        runBlocking {
            val profile = deviceProfile()
            val api = RecordingDeviceApi(deviceSessions())
            val factoryProfiles = mutableListOf<ServerProfileId>()
            val manager =
                ApiPersonalDeviceSessions(
                    profiles = FakeProfileRepository(profile),
                    credentials = FakeSessionStore(),
                    apiFactory = { selected, credential ->
                        factoryProfiles += selected.id
                        assertEquals(ACCESS_TOKEN, credential?.value)
                        api.proxy
                    },
                )

            val snapshot = manager.load(profile.id)

            assertEquals(profile.id, snapshot.serverProfileId)
            assertEquals(listOf(CURRENT_ID, REMOTE_ID), snapshot.items.map(DeviceSession::id))
            assertEquals(listOf(profile.id), factoryProfiles)
            assertEquals(1, api.listCalls)
        }

    @Test
    fun `revoking another device rechecks the inventory and retains the local session`() =
        runBlocking {
            val profile = deviceProfile()
            val store = FakeSessionStore()
            val api = RecordingDeviceApi(deviceSessions())
            val manager = deviceManager(profile, store, api)

            val revoked = manager.revoke(profile.id, REMOTE_ID)

            assertEquals(REMOTE_ID, revoked.id)
            assertEquals(false, revoked.current)
            assertEquals(1, api.listCalls)
            assertEquals(listOf(REMOTE_ID), api.revocations)
            assertEquals(0, store.removals)
        }

    @Test
    fun `revoking the current device removes the local session only after remote success`() =
        runBlocking {
            val profile = deviceProfile()
            val store = FakeSessionStore()
            val api = RecordingDeviceApi(deviceSessions())
            val manager = deviceManager(profile, store, api)

            val revoked = manager.revoke(profile.id, CURRENT_ID)

            assertEquals(true, revoked.current)
            assertEquals(listOf(CURRENT_ID), api.revocations)
            assertEquals(1, store.removals)
            assertEquals(null, store.session)
        }

    @Test
    fun `a stale confirmation cannot revoke a different or missing device`() {
        val profile = deviceProfile()
        val store = FakeSessionStore()
        val api = RecordingDeviceApi(deviceSessions())
        val manager = deviceManager(profile, store, api)

        assertThrows(PersonalDeviceSessionNotFoundException::class.java) {
            runBlocking { manager.revoke(profile.id, MISSING_ID) }
        }

        assertEquals(1, api.listCalls)
        assertEquals(emptyList<String>(), api.revocations)
        assertEquals(0, store.removals)
    }

    @Test
    fun `missing profile fails before credentials or API construction`() {
        val profile = deviceProfile()
        val store = FakeSessionStore()
        var factoryCalls = 0
        val manager =
            ApiPersonalDeviceSessions(
                profiles = FakeProfileRepository(),
                credentials = store,
                apiFactory = { _, _ ->
                    factoryCalls += 1
                    RecordingDeviceApi(deviceSessions()).proxy
                },
            )

        assertThrows(PersonalDeviceSessionsProfileUnavailableException::class.java) {
            runBlocking { manager.load(profile.id) }
        }
        assertEquals(0, factoryCalls)
        assertEquals(0, store.reads)
    }

    private fun deviceManager(
        profile: ServerProfile,
        store: FakeSessionStore,
        api: RecordingDeviceApi,
    ): ApiPersonalDeviceSessions =
        ApiPersonalDeviceSessions(
            profiles = FakeProfileRepository(profile),
            credentials = store,
            apiFactory = { _, _ -> api.proxy },
        )

    private class FakeProfileRepository(
        vararg profiles: ServerProfile,
    ) : ServerProfileRepository {
        private val values = MutableStateFlow(profiles.toList())

        override fun observeAll(): Flow<List<ServerProfile>> = values

        override suspend fun find(id: ServerProfileId): ServerProfile? = values.value.firstOrNull { it.id == id }

        override suspend fun save(profile: ServerProfile) = Unit

        override suspend fun delete(id: ServerProfileId) = Unit
    }

    private class FakeSessionStore : ServerCredentialStore {
        var session: StoredServerSession? =
            StoredServerSession(
                accessCredential = BearerCredential(ACCESS_TOKEN),
                refreshCredential = RefreshCredential(REFRESH_TOKEN),
                accessExpiresAt = Instant.parse("2099-01-01T00:00:00Z"),
                refreshExpiresAt = Instant.parse("2099-02-01T00:00:00Z"),
            )
        var reads = 0
        var removals = 0

        override suspend fun write(
            profileId: ServerProfileId,
            credential: ByteArray,
        ) = error("legacy write is not expected")

        override suspend fun read(profileId: ServerProfileId): ByteArray? = session?.accessCredential?.value?.encodeToByteArray()

        override suspend fun readSession(profileId: ServerProfileId): StoredServerSession? {
            reads += 1
            return session
        }

        override suspend fun remove(profileId: ServerProfileId) {
            removals += 1
            session = null
        }
    }

    private class RecordingDeviceApi(
        private val sessions: List<DeviceSession>,
    ) {
        var listCalls = 0
        val revocations = mutableListOf<String>()
        val proxy: VoiceAssetApi =
            Proxy.newProxyInstance(
                VoiceAssetApi::class.java.classLoader,
                arrayOf(VoiceAssetApi::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "listDeviceSessions" -> {
                        listCalls += 1
                        DeviceSessionList(sessions)
                    }
                    "revokeDeviceSession" -> {
                        revocations += arguments?.get(0) as String
                        null
                    }
                    "toString" -> "RecordingDeviceApi"
                    else -> error("unexpected VoiceAssetApi call: ${method.name}")
                }
            } as VoiceAssetApi
    }

    companion object {
        const val CURRENT_ID = "40000000-0000-4000-8000-000000000004"
        const val REMOTE_ID = "50000000-0000-4000-8000-000000000005"
        const val MISSING_ID = "60000000-0000-4000-8000-000000000006"
        const val ACCESS_TOKEN = "va_access_token_with_sufficient_entropy"
        val REFRESH_TOKEN = "va_rft_${"r".repeat(43)}"
    }
}

private fun deviceProfile(): ServerProfile =
    ServerProfile.create(
        id = ServerProfileId.parse("30000000-0000-4000-8000-000000000003"),
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

private fun deviceSessions(): List<DeviceSession> =
    listOf(
        DeviceSession(
            id = PersonalDeviceSessionsTest.CURRENT_ID,
            deviceName = "Pixel 9",
            current = true,
            createdAt = "2026-07-17T00:00:00Z",
            lastSeenAt = "2026-07-18T05:30:00Z",
            expiresAt = "2026-07-18T17:30:00Z",
            refreshExpiresAt = "2026-08-17T05:30:00Z",
        ),
        DeviceSession(
            id = PersonalDeviceSessionsTest.REMOTE_ID,
            deviceName = "Tablet",
            current = false,
            createdAt = "2026-07-16T00:00:00Z",
            lastSeenAt = "2026-07-17T05:30:00Z",
            expiresAt = "2026-07-17T17:30:00Z",
            refreshExpiresAt = "2026-08-16T05:30:00Z",
        ),
    )
