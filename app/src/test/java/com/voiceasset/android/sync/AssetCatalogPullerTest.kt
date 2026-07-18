package com.voiceasset.android.sync

import com.voiceasset.android.data.CachedRemoteAsset
import com.voiceasset.android.data.IncrementalSyncCheckpoint
import com.voiceasset.android.data.IncrementalSyncStore
import com.voiceasset.core.api.Asset
import com.voiceasset.core.api.AssetList
import com.voiceasset.core.api.SyncChangeList
import com.voiceasset.core.api.VoiceAssetProtocolException
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssetCatalogPullerTest {
    @Test
    fun `pull merges every bounded page from the beginning`() =
        runBlocking {
            val profileId = profileId()
            val pages =
                ArrayDeque(
                    listOf(
                        AssetList(listOf(asset("30000000-0000-4000-8000-000000000003")), "cursor-1"),
                        AssetList(listOf(asset("30000000-0000-4000-8000-000000000002"))),
                    ),
                )
            val requests = mutableListOf<Pair<String?, Int>>()
            val store = CatalogStore()

            val result =
                AssetCatalogPuller(
                    fetchPage = { cursor, limit ->
                        requests += cursor to limit
                        pages.removeFirst()
                    },
                    store = store,
                ).pull(profileId)

            assertEquals(listOf(null to 100, "cursor-1" to 100), requests)
            assertEquals(AssetCatalogPullResult(2, 2, 2), result)
            assertEquals(2, store.pages.size)
        }

    @Test
    fun `pull rejects repeated assets and cross workspace pagination`() {
        val profileId = profileId()
        val repeated = asset("30000000-0000-4000-8000-000000000003")
        val repeatedPages =
            ArrayDeque(
                listOf(
                    AssetList(listOf(repeated), "cursor-1"),
                    AssetList(listOf(repeated)),
                ),
            )
        assertThrows(VoiceAssetProtocolException::class.java) {
            runBlocking {
                AssetCatalogPuller(
                    fetchPage = { _, _ -> repeatedPages.removeFirst() },
                    store = CatalogStore(),
                ).pull(profileId)
            }
        }

        val otherWorkspace = asset("30000000-0000-4000-8000-000000000002", workspaceId = OTHER_WORKSPACE_ID)
        val workspacePages =
            ArrayDeque(
                listOf(
                    AssetList(listOf(repeated), "cursor-1"),
                    AssetList(listOf(otherWorkspace)),
                ),
            )
        assertThrows(VoiceAssetProtocolException::class.java) {
            runBlocking {
                AssetCatalogPuller(
                    fetchPage = { _, _ -> workspacePages.removeFirst() },
                    store = CatalogStore(),
                ).pull(profileId)
            }
        }
    }

    @Test
    fun `pull rejects non-progressing and over-bound catalogs`() {
        val profileId = profileId()
        assertThrows(VoiceAssetProtocolException::class.java) {
            runBlocking {
                AssetCatalogPuller(
                    fetchPage = { _, _ -> AssetList(emptyList(), "cursor-1") },
                    store = CatalogStore(),
                ).pull(profileId)
            }
        }
        assertThrows(VoiceAssetProtocolException::class.java) {
            runBlocking {
                AssetCatalogPuller(
                    fetchPage = { _, _ ->
                        AssetList(listOf(asset("30000000-0000-4000-8000-000000000003")), "cursor-1")
                    },
                    store = CatalogStore(),
                    maxPages = 1,
                ).pull(profileId)
            }
        }
    }

    private class CatalogStore : IncrementalSyncStore {
        val pages = mutableListOf<AssetList>()

        override fun observeAssets(serverProfileId: ServerProfileId): Flow<List<CachedRemoteAsset>> = flowOf(emptyList())

        override suspend fun checkpoint(serverProfileId: ServerProfileId): IncrementalSyncCheckpoint? = null

        override suspend fun refreshAsset(
            serverProfileId: ServerProfileId,
            asset: Asset,
        ): CachedRemoteAsset = error("not used")

        override suspend fun mergeCatalogPage(
            serverProfileId: ServerProfileId,
            page: AssetList,
        ): Int {
            pages += page
            return page.items.size
        }

        override suspend fun applyPage(
            serverProfileId: ServerProfileId,
            expectedCursor: String?,
            page: SyncChangeList,
            appliedAtEpochMillis: Long,
        ): IncrementalSyncCheckpoint = error("not used")
    }

    private fun profileId(): ServerProfileId = ServerProfileId.parse("10000000-0000-4000-8000-000000000001")

    private fun asset(
        id: String,
        workspaceId: String = WORKSPACE_ID,
    ): Asset =
        Asset(
            id = id,
            workspaceId = workspaceId,
            collectionId = null,
            title = "Remote asset",
            language = "en-US",
            status = "ready",
            durationMillis = 1_000,
            version = 1,
            createdAt = "2026-07-17T20:00:00Z",
            updatedAt = "2026-07-17T20:00:00Z",
        )

    private companion object {
        const val WORKSPACE_ID = "20000000-0000-4000-8000-000000000002"
        const val OTHER_WORKSPACE_ID = "20000000-0000-4000-8000-000000000003"
    }
}
