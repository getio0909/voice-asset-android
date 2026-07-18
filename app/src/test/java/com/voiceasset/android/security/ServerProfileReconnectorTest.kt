package com.voiceasset.android.security

import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.LoginResult
import com.voiceasset.core.api.Principal
import com.voiceasset.core.api.RefreshCredential
import com.voiceasset.core.api.ServerProfileAuthenticator
import com.voiceasset.core.api.VoiceAssetApiException
import com.voiceasset.core.api.WebSession
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

class ServerProfileReconnectorTest {
    @Test
    fun `reconnect replaces the complete session for the exact existing profile`() =
        runBlocking {
            val profile = reconnectProfile()
            val store = ReconnectSessionStore()
            val authenticator = RecordingReconnectAuthenticator()
            val reconnector =
                AuthenticatedServerProfileReconnector(
                    profiles = ReconnectProfileRepository(profile),
                    credentials = store,
                    authenticator = authenticator,
                )

            reconnector.reconnect(profile.id, " owner@example.test ", "new-password")

            assertEquals(profile.id, authenticator.profileId)
            assertEquals("owner@example.test", authenticator.email)
            assertEquals("new-password", authenticator.password)
            assertEquals(NEW_ACCESS_TOKEN, store.session?.accessCredential?.value)
            assertEquals(NEW_REFRESH_TOKEN, store.session?.refreshCredential?.value)
            assertEquals(1, store.writes)
            assertEquals(0, store.removals)
        }

    @Test
    fun `rejected authentication preserves the existing local session`() {
        val profile = reconnectProfile()
        val store = ReconnectSessionStore(reconnectStoredSession(OLD_ACCESS_TOKEN))
        val failure = VoiceAssetApiException(401, "unauthorized", null, "login rejected")
        val reconnector =
            AuthenticatedServerProfileReconnector(
                profiles = ReconnectProfileRepository(profile),
                credentials = store,
                authenticator = RecordingReconnectAuthenticator(failure),
            )

        assertThrows(VoiceAssetApiException::class.java) {
            runBlocking { reconnector.reconnect(profile.id, "owner@example.test", "wrong-password") }
        }
        assertEquals(OLD_ACCESS_TOKEN, store.session?.accessCredential?.value)
        assertEquals(0, store.removals)
    }

    @Test
    fun `failed persistence removes a possibly partial replacement after authentication`() {
        val profile = reconnectProfile()
        val store =
            ReconnectSessionStore(
                session = reconnectStoredSession(OLD_ACCESS_TOKEN),
                writeFailure = CredentialStoreException("disk unavailable"),
            )
        val reconnector =
            AuthenticatedServerProfileReconnector(
                profiles = ReconnectProfileRepository(profile),
                credentials = store,
                authenticator = RecordingReconnectAuthenticator(),
            )

        assertThrows(CredentialStoreException::class.java) {
            runBlocking { reconnector.reconnect(profile.id, "owner@example.test", "new-password") }
        }
        assertEquals(null, store.session)
        assertEquals(1, store.removals)
    }

    @Test
    fun `missing profile fails before authentication or credential mutation`() {
        val profile = reconnectProfile()
        val store = ReconnectSessionStore(reconnectStoredSession(OLD_ACCESS_TOKEN))
        val authenticator = RecordingReconnectAuthenticator()
        val reconnector =
            AuthenticatedServerProfileReconnector(
                profiles = ReconnectProfileRepository(),
                credentials = store,
                authenticator = authenticator,
            )

        assertThrows(ServerProfileReconnectProfileUnavailableException::class.java) {
            runBlocking { reconnector.reconnect(profile.id, "owner@example.test", "new-password") }
        }
        assertEquals(0, authenticator.calls)
        assertEquals(0, store.writes)
        assertEquals(0, store.removals)
    }

    private class RecordingReconnectAuthenticator(
        private val failure: Exception? = null,
    ) : ServerProfileAuthenticator {
        var calls = 0
        var profileId: ServerProfileId? = null
        var email: String? = null
        var password: String? = null

        override fun authenticate(
            profile: ServerProfile,
            email: String,
            password: String,
        ): LoginResult {
            calls += 1
            profileId = profile.id
            this.email = email
            this.password = password
            failure?.let { throw it }
            return reconnectLoginResult()
        }
    }

    private class ReconnectProfileRepository(
        vararg profiles: ServerProfile,
    ) : ServerProfileRepository {
        private val values = MutableStateFlow(profiles.toList())

        override fun observeAll(): Flow<List<ServerProfile>> = values

        override suspend fun find(id: ServerProfileId): ServerProfile? = values.value.firstOrNull { it.id == id }

        override suspend fun save(profile: ServerProfile) = Unit

        override suspend fun delete(id: ServerProfileId) = Unit
    }

    private class ReconnectSessionStore(
        var session: StoredServerSession? = null,
        private val writeFailure: Exception? = null,
    ) : ServerCredentialStore {
        var writes = 0
        var removals = 0

        override suspend fun write(
            profileId: ServerProfileId,
            credential: ByteArray,
        ) = error("legacy write is not expected")

        override suspend fun read(profileId: ServerProfileId): ByteArray? = session?.accessCredential?.value?.encodeToByteArray()

        override suspend fun writeSession(
            profileId: ServerProfileId,
            session: StoredServerSession,
        ) {
            writes += 1
            this.session = session
            writeFailure?.let { throw it }
        }

        override suspend fun readSession(profileId: ServerProfileId): StoredServerSession? = session

        override suspend fun remove(profileId: ServerProfileId) {
            removals += 1
            session = null
        }
    }

    companion object {
        const val OLD_ACCESS_TOKEN = "va_existing_access_token_with_entropy"
        const val NEW_ACCESS_TOKEN = "va_reconnected_access_token_with_entropy"
        val NEW_REFRESH_TOKEN = "va_rft_${"n".repeat(43)}"
    }
}

private fun reconnectProfile(): ServerProfile =
    ServerProfile.create(
        id = ServerProfileId.parse("95000000-0000-4000-8000-000000000009"),
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

private fun reconnectStoredSession(accessToken: String): StoredServerSession =
    StoredServerSession(
        accessCredential = BearerCredential(accessToken),
        refreshCredential = RefreshCredential("va_rft_${"o".repeat(43)}"),
        accessExpiresAt = java.time.Instant.parse("2099-01-01T00:00:00Z"),
        refreshExpiresAt = java.time.Instant.parse("2099-02-01T00:00:00Z"),
    )

private fun reconnectLoginResult(): LoginResult =
    LoginResult(
        session =
            WebSession(
                expiresAt = "2099-01-01T00:00:00Z",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
                user =
                    Principal(
                        id = "10000000-0000-4000-8000-000000000001",
                        workspaceId = "20000000-0000-4000-8000-000000000002",
                        role = "owner",
                        email = "owner@example.test",
                        scopes = listOf("assets:read"),
                    ),
            ),
        credential = BearerCredential(ServerProfileReconnectorTest.NEW_ACCESS_TOKEN),
        refreshCredential = RefreshCredential(ServerProfileReconnectorTest.NEW_REFRESH_TOKEN),
    )
