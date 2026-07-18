package com.voiceasset.android.security

import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StartupSyncPolicyTest {
    @Test
    fun `only profiles with readable sessions resume remote work`() =
        runBlocking {
            val authenticated = profile("b0000000-0000-4000-8000-000000000001")
            val missing = profile("b0000000-0000-4000-8000-000000000002")
            val unreadable = profile("b0000000-0000-4000-8000-000000000003")
            val credentials =
                FakeCredentialStore(
                    sessions =
                        mapOf(
                            authenticated.id to
                                StoredServerSession.legacy(
                                    BearerCredential("va_startup_token_with_sufficient_entropy"),
                                ),
                        ),
                    unreadable = setOf(unreadable.id),
                )

            val selected =
                StartupSyncPolicy(credentials).authenticatedProfileIds(
                    listOf(authenticated, missing, unreadable),
                )

            assertEquals(setOf(authenticated.id), selected)
        }

    @Test
    fun `missing or unreadable sessions cannot schedule immediate sync`() =
        runBlocking {
            val missing = profile("b0000000-0000-4000-8000-000000000004")
            val unreadable = profile("b0000000-0000-4000-8000-000000000005")
            val policy =
                StartupSyncPolicy(
                    FakeCredentialStore(
                        sessions = emptyMap(),
                        unreadable = setOf(unreadable.id),
                    ),
                )

            assertFalse(policy.hasReadableSession(missing.id))
            assertFalse(policy.hasReadableSession(unreadable.id))
        }

    private fun profile(value: String): ServerProfile =
        ServerProfile.create(
            id = ServerProfileId.parse(value),
            name = value,
            baseUrl = "https://example.test",
            authenticationMode = AuthenticationMode.LOCAL_SESSION,
            defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
            defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
            customCaPem = null,
            certificateFingerprint = null,
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 1_000,
        )

    private class FakeCredentialStore(
        private val sessions: Map<ServerProfileId, StoredServerSession>,
        private val unreadable: Set<ServerProfileId>,
    ) : ServerCredentialStore {
        override suspend fun write(
            profileId: ServerProfileId,
            credential: ByteArray,
        ) = Unit

        override suspend fun read(profileId: ServerProfileId): ByteArray? = null

        override suspend fun readSession(profileId: ServerProfileId): StoredServerSession? {
            if (profileId in unreadable) {
                error("credential store unavailable")
            }
            return sessions[profileId]
        }

        override suspend fun remove(profileId: ServerProfileId) = Unit
    }
}
