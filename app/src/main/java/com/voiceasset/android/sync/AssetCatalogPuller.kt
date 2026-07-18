package com.voiceasset.android.sync

import com.voiceasset.android.data.IncrementalSyncStore
import com.voiceasset.core.api.AssetList
import com.voiceasset.core.api.VoiceAssetProtocolException
import com.voiceasset.core.model.ServerProfileId

data class AssetCatalogPullResult(
    val pagesApplied: Int,
    val assetsSeen: Int,
    val assetsMerged: Int,
)

class AssetCatalogPuller(
    private val fetchPage: (cursor: String?, limit: Int) -> AssetList,
    private val store: IncrementalSyncStore,
    private val pageLimit: Int = 100,
    private val maxPages: Int = 100,
) {
    init {
        require(pageLimit in 1..100) { "asset catalog page limit is invalid" }
        require(maxPages in 1..100) { "asset catalog page bound is invalid" }
    }

    suspend fun pull(serverProfileId: ServerProfileId): AssetCatalogPullResult {
        var cursor: String? = null
        var pagesApplied = 0
        var assetsMerged = 0
        val assetIds = HashSet<String>()
        var workspaceId: String? = null
        do {
            val page = fetchPage(cursor, pageLimit)
            if (page.nextCursor != null && page.items.isEmpty()) {
                throw VoiceAssetProtocolException("Server returned a non-progressing asset catalog page.")
            }
            if (page.nextCursor != null && page.nextCursor == cursor) {
                throw VoiceAssetProtocolException("Server did not advance the asset catalog cursor.")
            }
            page.items.forEach { asset ->
                if (!assetIds.add(asset.id)) {
                    throw VoiceAssetProtocolException("Server repeated an asset across catalog pages.")
                }
                val expectedWorkspace = workspaceId
                if (expectedWorkspace == null) {
                    workspaceId = asset.workspaceId
                } else if (asset.workspaceId != expectedWorkspace) {
                    throw VoiceAssetProtocolException("Server changed workspace across asset catalog pages.")
                }
            }
            assetsMerged += store.mergeCatalogPage(serverProfileId, page)
            pagesApplied += 1
            cursor = page.nextCursor
            if (cursor != null && pagesApplied >= maxPages) {
                throw VoiceAssetProtocolException("Asset catalog exceeds the bounded mobile bootstrap limit.")
            }
        } while (cursor != null)
        return AssetCatalogPullResult(
            pagesApplied = pagesApplied,
            assetsSeen = assetIds.size,
            assetsMerged = assetsMerged,
        )
    }
}
