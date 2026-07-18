package com.voiceasset.android.security

import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.LoginResult
import com.voiceasset.core.api.RefreshCredential
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.api.VoiceAssetApiException
import com.voiceasset.core.api.WebSession
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant

class ServerSessionProviderTest {
    @Test
    fun `session codec strictly round trips both credentials and expiries without disclosure`() {
        val session = storedSession()

        val encoded = ServerSessionCodec.encode(session)
        val decoded = ServerSessionCodec.decode(encoded)

        assertEquals(ACCESS_TOKEN, decoded.accessCredential.value)
        assertEquals(REFRESH_TOKEN, decoded.refreshCredential?.value)
        assertEquals(ACCESS_EXPIRY, decoded.accessExpiresAt)
        assertEquals(REFRESH_EXPIRY, decoded.refreshExpiresAt)
        assertFalse(session.toString().contains(ACCESS_TOKEN))
        assertFalse(session.toString().contains(REFRESH_TOKEN))
        encoded.fill(0)
    }

    @Test
    fun `session codec rejects truncated or trailing plaintext`() {
        val encoded = ServerSessionCodec.encode(storedSession())
        val truncated = encoded.copyOf(encoded.size - 1)
        val trailing = encoded + byteArrayOf(0)

        assertThrows(CredentialStoreException::class.java) { ServerSessionCodec.decode(truncated) }
        assertThrows(CredentialStoreException::class.java) { ServerSessionCodec.decode(trailing) }
        encoded.fill(0)
        truncated.fill(0)
        trailing.fill(0)
    }

    @Test
    fun `fresh access session creates an authenticated API without rotation`() =
        runBlocking {
            val store = FakeSessionStore(storedSession())
            val apiFactory = RecordingApiFactory()
            val provider =
                RefreshingServerSessionProvider(
                    credentials = store,
                    apiFactory = apiFactory::create,
                    now = { NOW },
                    refreshWindow = Duration.ofMinutes(5),
                )

            provider.createApi(profile())

            assertEquals(listOf(ACCESS_TOKEN), apiFactory.createdWith)
            assertEquals(0, apiFactory.refreshCalls)
            assertEquals(0, store.sessionWrites)
        }

    @Test
    fun `concurrent callers rotate an expiring session exactly once and persist replacement`() =
        runBlocking {
            val store =
                FakeSessionStore(
                    storedSession(accessExpiresAt = NOW.plusSeconds(60)),
                )
            val apiFactory = RecordingApiFactory()
            val provider =
                RefreshingServerSessionProvider(
                    credentials = store,
                    apiFactory = apiFactory::create,
                    now = { NOW },
                    refreshWindow = Duration.ofMinutes(5),
                )

            List(8) { async { provider.createApi(profile()) } }.awaitAll()

            assertEquals(1, apiFactory.refreshCalls)
            assertEquals(1, store.sessionWrites)
            assertEquals(ROTATED_ACCESS_TOKEN, store.session?.accessCredential?.value)
            assertEquals(ROTATED_REFRESH_TOKEN, store.session?.refreshCredential?.value)
            assertEquals(8, apiFactory.createdWith.count { it == ROTATED_ACCESS_TOKEN })
        }

    @Test
    fun `rejected refresh removes unusable local session`() {
        val store = FakeSessionStore(storedSession(accessExpiresAt = NOW.minusSeconds(1)))
        val apiFactory = RecordingApiFactory(refreshFailure = unauthorized())
        val provider =
            RefreshingServerSessionProvider(
                credentials = store,
                apiFactory = apiFactory::create,
                now = { NOW },
                refreshWindow = Duration.ofMinutes(5),
            )

        assertThrows(ServerSessionAuthenticationRequiredException::class.java) {
            runBlocking { provider.createApi(profile()) }
        }
        assertEquals(1, store.removals)
        assertEquals(null, store.session)
    }

    @Test
    fun `login result conversion rejects inverted or expired refresh lifetime`() {
        val invalid =
            loginResult(
                accessExpiresAt = NOW.plusSeconds(600),
                refreshExpiresAt = NOW.plusSeconds(300),
            )

        assertThrows(CredentialStoreException::class.java) {
            StoredServerSession.fromLogin(invalid, NOW)
        }
    }

    private class FakeSessionStore(
        var session: StoredServerSession?,
    ) : ServerCredentialStore {
        var sessionWrites = 0
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
            sessionWrites += 1
            this.session = session
        }

        override suspend fun readSession(profileId: ServerProfileId): StoredServerSession? = session

        override suspend fun remove(profileId: ServerProfileId) {
            removals += 1
            session = null
        }
    }

    private class RecordingApiFactory(
        private val refreshFailure: VoiceAssetApiException? = null,
    ) {
        val createdWith = mutableListOf<String?>()
        var refreshCalls = 0

        fun create(
            profile: ServerProfile,
            credential: BearerCredential?,
        ): VoiceAssetApi {
            createdWith += credential?.value
            return Proxy.newProxyInstance(
                VoiceAssetApi::class.java.classLoader,
                arrayOf(VoiceAssetApi::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "refreshSession" -> {
                        refreshCalls += 1
                        refreshFailure?.let { throw it }
                        loginResult(
                            accessToken = ROTATED_ACCESS_TOKEN,
                            refreshToken = ROTATED_REFRESH_TOKEN,
                            accessExpiresAt = NOW.plusSeconds(12 * 60 * 60),
                            refreshExpiresAt = NOW.plusSeconds(30 * 24 * 60 * 60L),
                        )
                    }
                    "toString" -> "RecordingApi"
                    else -> defaultValue(method.returnType)
                }
            } as VoiceAssetApi
        }
    }
}

private fun storedSession(accessExpiresAt: Instant = ACCESS_EXPIRY): StoredServerSession =
    StoredServerSession(
        accessCredential = BearerCredential(ACCESS_TOKEN),
        refreshCredential = RefreshCredential(REFRESH_TOKEN),
        accessExpiresAt = accessExpiresAt,
        refreshExpiresAt = REFRESH_EXPIRY,
    )

private fun loginResult(
    accessToken: String = ACCESS_TOKEN,
    refreshToken: String = REFRESH_TOKEN,
    accessExpiresAt: Instant = ACCESS_EXPIRY,
    refreshExpiresAt: Instant = REFRESH_EXPIRY,
): LoginResult =
    LoginResult(
        session =
            WebSession(
                expiresAt = accessExpiresAt.toString(),
                refreshExpiresAt = refreshExpiresAt.toString(),
                user =
                    com.voiceasset.core.api.Principal(
                        id = "10000000-0000-4000-8000-000000000001",
                        workspaceId = "20000000-0000-4000-8000-000000000002",
                        role = "owner",
                        email = "owner@example.com",
                        scopes = listOf("assets:read"),
                    ),
            ),
        credential = BearerCredential(accessToken),
        refreshCredential = RefreshCredential(refreshToken),
    )

private fun profile(): ServerProfile =
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

private fun unauthorized(): VoiceAssetApiException =
    VoiceAssetApiException(
        statusCode = 401,
        code = "unauthorized",
        requestId = "40000000-0000-4000-8000-000000000004",
        message = "authentication required",
    )

private fun defaultValue(type: Class<*>): Any? =
    when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Void.TYPE -> null
        else -> null
    }

private val NOW: Instant = Instant.parse("2026-07-18T05:30:00Z")
private val ACCESS_EXPIRY: Instant = NOW.plusSeconds(12 * 60 * 60)
private val REFRESH_EXPIRY: Instant = NOW.plusSeconds(30 * 24 * 60 * 60L)
private const val ACCESS_TOKEN = "va_access_token_with_sufficient_entropy"
private val REFRESH_TOKEN = "va_rft_${"r".repeat(43)}"
private const val ROTATED_ACCESS_TOKEN = "va_rotated_access_with_sufficient_entropy"
private val ROTATED_REFRESH_TOKEN = "va_rft_${"n".repeat(43)}"
