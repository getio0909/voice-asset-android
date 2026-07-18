package com.voiceasset.android.asset

import com.voiceasset.android.data.CachedRemoteAsset
import com.voiceasset.android.data.IncrementalSyncCheckpoint
import com.voiceasset.android.data.IncrementalSyncStore
import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.android.security.ServerCredentialStore
import com.voiceasset.core.api.Asset
import com.voiceasset.core.api.AssetList
import com.voiceasset.core.api.SyncChangeList
import com.voiceasset.core.api.UpdateAssetMetadataRequest
import com.voiceasset.core.api.VersionedAsset
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

class AssetMetadataEditorTest {
    @Test
    fun `load and save reuse the fetched ETag refresh the cache and clear credential bytes`() =
        runBlocking {
            val profile = profile()
            val credentials = FakeCredentialStore(TOKEN.encodeToByteArray())
            val cache = FakeIncrementalSyncStore(cachedAsset(profile.id, version = 3, title = "Cached"))
            val api = RecordingAssetApi()
            val factoryCredentials = mutableListOf<String>()
            val editor =
                ApiAssetMetadataEditor(
                    profiles = FakeServerProfileRepository(profile),
                    credentials = credentials,
                    incrementalSync = cache,
                    apiFactory = { _, credential ->
                        factoryCredentials += requireNotNull(credential).value
                        api.proxy
                    },
                )

            val session = editor.load(profile.id, ASSET_ID)
            val saved = editor.save(session, " Android title ", "zh-CN", COLLECTION_ID)

            assertEquals(4L, session.version)
            assertEquals("\"4\"", session.expectedEntityTag)
            assertEquals(listOf(TOKEN, TOKEN), factoryCredentials)
            assertEquals("\"4\"", api.updateEntityTag)
            assertEquals(" Android title ", api.updateInput?.title)
            assertEquals(COLLECTION_ID, api.updateInput?.collectionId)
            assertEquals(5L, saved.version)
            assertEquals(
                "Android title",
                cache.assets.value
                    .single()
                    .title,
            )
            assertTrue(credentials.returnedBuffers.all { bytes -> bytes.all { it == 0.toByte() } })
        }

    @Test
    fun `missing profile or credential fails before API construction`() {
        val profile = profile()
        var factoryCalls = 0
        val noProfile =
            ApiAssetMetadataEditor(
                profiles = FakeServerProfileRepository(),
                credentials = FakeCredentialStore(TOKEN.encodeToByteArray()),
                incrementalSync = FakeIncrementalSyncStore(cachedAsset(profile.id, 1, "Cached")),
                apiFactory = { _, _ ->
                    factoryCalls += 1
                    RecordingAssetApi().proxy
                },
            )
        assertThrows(AssetMetadataProfileUnavailableException::class.java) {
            runBlocking { noProfile.load(profile.id, ASSET_ID) }
        }
        val noCredential =
            ApiAssetMetadataEditor(
                profiles = FakeServerProfileRepository(profile),
                credentials = FakeCredentialStore(null),
                incrementalSync = FakeIncrementalSyncStore(cachedAsset(profile.id, 1, "Cached")),
                apiFactory = { _, _ ->
                    factoryCalls += 1
                    RecordingAssetApi().proxy
                },
            )
        assertThrows(AssetMetadataAuthenticationRequiredException::class.java) {
            runBlocking { noCredential.load(profile.id, ASSET_ID) }
        }
        val invalidCredentials = FakeCredentialStore("not-a-token".encodeToByteArray())
        val invalidCredential =
            ApiAssetMetadataEditor(
                profiles = FakeServerProfileRepository(profile),
                credentials = invalidCredentials,
                incrementalSync = FakeIncrementalSyncStore(cachedAsset(profile.id, 1, "Cached")),
                apiFactory = { _, _ ->
                    factoryCalls += 1
                    RecordingAssetApi().proxy
                },
            )
        assertThrows(AssetMetadataAuthenticationRequiredException::class.java) {
            runBlocking { invalidCredential.load(profile.id, ASSET_ID) }
        }
        assertTrue(invalidCredentials.returnedBuffers.single().all { it == 0.toByte() })
        assertEquals(0, factoryCalls)
    }

    private class RecordingAssetApi {
        var updateEntityTag: String? = null
        var updateInput: UpdateAssetMetadataRequest? = null
        val proxy: VoiceAssetApi =
            Proxy.newProxyInstance(
                VoiceAssetApi::class.java.classLoader,
                arrayOf(VoiceAssetApi::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "getAsset" -> VersionedAsset(asset(4, "Latest server title"), "\"4\"")
                    "updateAssetMetadata" -> {
                        updateEntityTag = arguments?.get(1) as String
                        updateInput = arguments[2] as UpdateAssetMetadataRequest
                        VersionedAsset(
                            asset(
                                version = 5,
                                title = updateInput?.title?.trim().orEmpty(),
                                language = updateInput?.language.orEmpty(),
                                collectionId = updateInput?.collectionId,
                            ),
                            "\"5\"",
                        )
                    }
                    "toString" -> "RecordingAssetApi"
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

        override suspend fun save(profile: ServerProfile) {
            values.update { current -> current.filterNot { it.id == profile.id } + profile }
        }

        override suspend fun delete(id: ServerProfileId) {
            values.update { current -> current.filterNot { it.id == id } }
        }
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

    private class FakeIncrementalSyncStore(
        initial: CachedRemoteAsset,
    ) : IncrementalSyncStore {
        val assets = MutableStateFlow(listOf(initial))

        override fun observeAssets(serverProfileId: ServerProfileId): Flow<List<CachedRemoteAsset>> = assets

        override suspend fun checkpoint(serverProfileId: ServerProfileId): IncrementalSyncCheckpoint? = null

        override suspend fun refreshAsset(
            serverProfileId: ServerProfileId,
            asset: Asset,
        ): CachedRemoteAsset {
            val existing = assets.value.single { it.assetId == asset.id }
            val refreshed =
                existing.copy(
                    collectionId = asset.collectionId,
                    title = asset.title,
                    language = asset.language,
                    status = asset.status,
                    durationMillis = asset.durationMillis,
                    version = asset.version,
                    createdAtEpochMillis = Instant.parse(asset.createdAt).toEpochMilli(),
                    updatedAtEpochMillis = Instant.parse(asset.updatedAt).toEpochMilli(),
                )
            assets.value = listOf(refreshed)
            return refreshed
        }

        override suspend fun mergeCatalogPage(
            serverProfileId: ServerProfileId,
            page: AssetList,
        ): Int = error("not used")

        override suspend fun applyPage(
            serverProfileId: ServerProfileId,
            expectedCursor: String?,
            page: SyncChangeList,
            appliedAtEpochMillis: Long,
        ): IncrementalSyncCheckpoint = error("not used")
    }

    private fun profile(): ServerProfile =
        ServerProfile.create(
            id = PROFILE_ID,
            name = "Metadata test",
            baseUrl = "https://example.test",
            authenticationMode = AuthenticationMode.LOCAL_SESSION,
            defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
            defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
            customCaPem = null,
            certificateFingerprint = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )

    private fun cachedAsset(
        profileId: ServerProfileId,
        version: Long,
        title: String,
    ): CachedRemoteAsset =
        CachedRemoteAsset(
            serverProfileId = profileId,
            assetId = ASSET_ID,
            collectionId = null,
            title = title,
            language = "en-US",
            status = "ready",
            durationMillis = 1_000,
            version = version,
            changeSequence = 3,
            createdAtEpochMillis = 1_721_113_200_000,
            updatedAtEpochMillis = 1_721_113_200_000,
            trashedAtEpochMillis = null,
        )

    companion object {
        private val PROFILE_ID = ServerProfileId.parse("10000000-0000-4000-8000-000000000001")
        private const val ASSET_ID = "20000000-0000-4000-8000-000000000002"
        private const val COLLECTION_ID = "30000000-0000-4000-8000-000000000003"
        private const val TOKEN = "va_test_session_1234567890"

        private fun asset(
            version: Long,
            title: String,
            language: String = "en-US",
            collectionId: String? = null,
        ): Asset =
            Asset(
                id = ASSET_ID,
                workspaceId = "40000000-0000-4000-8000-000000000004",
                collectionId = collectionId,
                title = title,
                language = language,
                status = "ready",
                durationMillis = 1_000,
                version = version,
                createdAt = "2026-07-16T08:00:00Z",
                updatedAt = "2026-07-16T08:01:00Z",
            )
    }
}
