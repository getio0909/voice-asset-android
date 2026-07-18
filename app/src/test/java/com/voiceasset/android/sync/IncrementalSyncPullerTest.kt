package com.voiceasset.android.sync

import com.voiceasset.android.data.CachedRemoteAsset
import com.voiceasset.android.data.IncrementalSyncCheckpoint
import com.voiceasset.android.data.IncrementalSyncCursorConflictException
import com.voiceasset.android.data.IncrementalSyncStore
import com.voiceasset.core.api.Asset
import com.voiceasset.core.api.AssetList
import com.voiceasset.core.api.SyncChange
import com.voiceasset.core.api.SyncChangeList
import com.voiceasset.core.api.VoiceAssetProtocolException
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalSyncPullerTest {
    @Test
    fun pullsEveryPageAndAdvancesTheDurableCursor() =
        runBlocking {
            val store = FakeIncrementalSyncStore()
            val requested = mutableListOf<Pair<String?, Int>>()
            val pages =
                ArrayDeque(
                    listOf(
                        SyncChangeList(listOf(deletion(1)), "cursor-1", true),
                        SyncChangeList(listOf(deletion(2)), "cursor-2", false),
                    ),
                )
            val result =
                IncrementalSyncPuller(
                    fetchPage = { cursor, limit ->
                        requested += cursor to limit
                        pages.removeFirst()
                    },
                    store = store,
                    clock = { 123L },
                ).pull(PROFILE_ID)

            assertEquals(listOf(null to 100, "cursor-1" to 100), requested)
            assertEquals(2, result.pagesApplied)
            assertEquals(2, result.changesApplied)
            assertFalse(result.hasMore)
            assertEquals("cursor-2", store.checkpoint(PROFILE_ID)?.cursor)
            assertEquals(2L, store.checkpoint(PROFILE_ID)?.lastSequence)
        }

    @Test
    fun rejectsAHasMorePageThatCannotAdvance() {
        val store = FakeIncrementalSyncStore()

        assertThrows(VoiceAssetProtocolException::class.java) {
            runBlocking {
                IncrementalSyncPuller(
                    fetchPage = { _, _ -> SyncChangeList(emptyList(), "cursor-1", true) },
                    store = store,
                ).pull(PROFILE_ID)
            }
        }
        assertEquals(null, runBlocking { store.checkpoint(PROFILE_ID) })
    }

    @Test
    fun boundsEachRunAndLeavesTheAdvancedCursorForRetry() =
        runBlocking {
            val store = FakeIncrementalSyncStore()
            val result =
                IncrementalSyncPuller(
                    fetchPage = { _, _ -> SyncChangeList(listOf(deletion(1)), "cursor-1", true) },
                    store = store,
                    maxPages = 1,
                ).pull(PROFILE_ID)

            assertTrue(result.hasMore)
            assertEquals(1, result.pagesApplied)
            assertEquals("cursor-1", store.checkpoint(PROFILE_ID)?.cursor)
        }

    private fun deletion(sequence: Long): SyncChange =
        SyncChange(
            sequence = sequence,
            entityType = "asset",
            entityId = "00000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}",
            operation = "delete",
            entityVersion = sequence,
            changedAt = "2026-07-17T21:00:00Z",
            asset = null,
        )

    private class FakeIncrementalSyncStore : IncrementalSyncStore {
        private var current: IncrementalSyncCheckpoint? = null

        override fun observeAssets(serverProfileId: ServerProfileId): Flow<List<CachedRemoteAsset>> = flowOf(emptyList())

        override suspend fun checkpoint(serverProfileId: ServerProfileId): IncrementalSyncCheckpoint? = current

        override suspend fun refreshAsset(
            serverProfileId: ServerProfileId,
            asset: Asset,
        ): CachedRemoteAsset = error("not used")

        override suspend fun mergeCatalogPage(
            serverProfileId: ServerProfileId,
            page: AssetList,
        ): Int = error("not used")

        override suspend fun applyPage(
            serverProfileId: ServerProfileId,
            expectedCursor: String?,
            page: SyncChangeList,
            appliedAtEpochMillis: Long,
        ): IncrementalSyncCheckpoint {
            if (current?.cursor != expectedCursor) {
                throw IncrementalSyncCursorConflictException()
            }
            val updated =
                IncrementalSyncCheckpoint(
                    serverProfileId = serverProfileId,
                    cursor = page.nextCursor,
                    lastSequence = page.items.lastOrNull()?.sequence ?: current?.lastSequence ?: 0,
                    updatedAtEpochMillis = appliedAtEpochMillis,
                )
            current = updated
            return updated
        }
    }

    private companion object {
        val PROFILE_ID = ServerProfileId.parse("00000000-0000-4000-8000-000000000001")
    }
}
