package com.voiceasset.android.sync

import com.voiceasset.android.data.IncrementalSyncStore
import com.voiceasset.core.api.SyncChangeList
import com.voiceasset.core.api.VoiceAssetProtocolException
import com.voiceasset.core.model.ServerProfileId

data class IncrementalSyncPullResult(
    val pagesApplied: Int,
    val changesApplied: Int,
    val hasMore: Boolean,
)

class IncrementalSyncPuller(
    private val fetchPage: (cursor: String?, limit: Int) -> SyncChangeList,
    private val store: IncrementalSyncStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pageLimit: Int = 100,
    private val maxPages: Int = 20,
) {
    init {
        require(pageLimit in 1..100) { "incremental sync page limit is invalid" }
        require(maxPages in 1..100) { "incremental sync page bound is invalid" }
    }

    suspend fun pull(serverProfileId: ServerProfileId): IncrementalSyncPullResult {
        var expectedCursor = store.checkpoint(serverProfileId)?.cursor
        var pagesApplied = 0
        var changesApplied = 0
        var hasMore: Boolean
        do {
            val page = fetchPage(expectedCursor, pageLimit)
            if (page.hasMore && page.items.isEmpty()) {
                throw VoiceAssetProtocolException("Server returned a non-progressing incremental sync page.")
            }
            if ((page.hasMore || page.items.isNotEmpty()) && page.nextCursor == expectedCursor) {
                throw VoiceAssetProtocolException("Server did not advance the incremental sync cursor.")
            }
            store.applyPage(
                serverProfileId = serverProfileId,
                expectedCursor = expectedCursor,
                page = page,
                appliedAtEpochMillis = clock(),
            )
            expectedCursor = page.nextCursor
            pagesApplied += 1
            changesApplied += page.items.size
            hasMore = page.hasMore
        } while (hasMore && pagesApplied < maxPages)
        return IncrementalSyncPullResult(
            pagesApplied = pagesApplied,
            changesApplied = changesApplied,
            hasMore = hasMore,
        )
    }
}
