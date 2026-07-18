package com.voiceasset.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceasset.core.api.Asset
import com.voiceasset.core.api.AssetList
import com.voiceasset.core.api.SyncAssetSnapshot
import com.voiceasset.core.api.SyncChange
import com.voiceasset.core.api.SyncChangeList
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncrementalSyncPersistenceTest {
    private lateinit var database: VoiceAssetDatabase
    private lateinit var profiles: RoomServerProfileRepository
    private lateinit var store: RoomIncrementalSyncStore

    @Before
    fun createDatabase() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database =
                Room
                    .inMemoryDatabaseBuilder(context, VoiceAssetDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            profiles = RoomServerProfileRepository(database.serverProfileDao())
            store = RoomIncrementalSyncStore(database.incrementalSyncDao())
            profiles.save(profile(PROFILE_ID))
        }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun appliesUpsertsDeletesAndCursorAtomically() =
        runBlocking {
            store.applyPage(PROFILE_ID, null, page(upsert(1, "First title"), "cursor-1"), 101)

            val cached = store.observeAssets(PROFILE_ID).first().single()
            assertEquals(ASSET_ID, cached.assetId)
            assertEquals("First title", cached.title)
            assertEquals(1L, cached.version)
            assertNull(database.incrementalSyncDao().findTombstone(PROFILE_ID.value, ASSET_ID))

            store.applyPage(PROFILE_ID, "cursor-1", page(deletion(2), "cursor-2"), 102)

            assertTrue(store.observeAssets(PROFILE_ID).first().isEmpty())
            val tombstone = database.incrementalSyncDao().findTombstone(PROFILE_ID.value, ASSET_ID)
            assertNotNull(tombstone)
            assertEquals(2L, tombstone?.version)
            assertEquals("cursor-2", store.checkpoint(PROFILE_ID)?.cursor)
            assertEquals(2L, store.checkpoint(PROFILE_ID)?.lastSequence)
        }

    @Test
    fun rollsBackTheWholePageWhenAChangeIsInvalid() =
        runBlocking {
            store.applyPage(PROFILE_ID, null, page(upsert(1, "Original"), "cursor-1"), 101)
            val invalid =
                SyncChangeList(
                    items =
                        listOf(
                            upsert(2, "Must roll back"),
                            deletion(3).copy(operation = "unsupported"),
                        ),
                    nextCursor = "cursor-3",
                    hasMore = false,
                )

            val result = runCatching { store.applyPage(PROFILE_ID, "cursor-1", invalid, 103) }

            assertTrue(result.isFailure)
            assertEquals(
                "Original",
                database.incrementalSyncDao().findAsset(PROFILE_ID.value, ASSET_ID)?.title,
            )
            assertEquals("cursor-1", store.checkpoint(PROFILE_ID)?.cursor)
            assertEquals(1L, store.checkpoint(PROFILE_ID)?.lastSequence)
        }

    @Test
    fun directRefreshPreservesTheCursorAndStaleSyncPagesCannotRegressIt() =
        runBlocking {
            store.applyPage(PROFILE_ID, null, page(upsert(1, "Original"), "cursor-1"), 101)

            val refreshed =
                store.refreshAsset(
                    PROFILE_ID,
                    asset(version = 3, title = "Edited on Android"),
                )

            assertEquals(3L, refreshed.version)
            assertEquals(1L, refreshed.changeSequence)
            assertEquals("cursor-1", store.checkpoint(PROFILE_ID)?.cursor)
            store.applyPage(PROFILE_ID, "cursor-1", page(upsert(2, "Stale server page"), "cursor-2"), 102)
            val afterStalePage = store.observeAssets(PROFILE_ID).first().single()
            assertEquals("Edited on Android", afterStalePage.title)
            assertEquals(3L, afterStalePage.version)
            assertEquals(1L, afterStalePage.changeSequence)
            assertEquals("cursor-2", store.checkpoint(PROFILE_ID)?.cursor)
            assertEquals(2L, store.checkpoint(PROFILE_ID)?.lastSequence)

            store.applyPage(PROFILE_ID, "cursor-2", page(upsert(4, "Newer server page"), "cursor-4"), 104)
            val afterNewerPage = store.observeAssets(PROFILE_ID).first().single()
            assertEquals("Newer server page", afterNewerPage.title)
            assertEquals(4L, afterNewerPage.version)
            assertEquals(4L, afterNewerPage.changeSequence)
        }

    @Test
    fun catalogBootstrapAddsMissingAssetsWithoutRegressingVersionsOrTombstones() =
        runBlocking {
            assertEquals(1, store.mergeCatalogPage(PROFILE_ID, AssetList(listOf(asset(1, "Catalog")))))
            assertNull(store.checkpoint(PROFILE_ID))
            assertEquals(
                0L,
                store
                    .observeAssets(PROFILE_ID)
                    .first()
                    .single()
                    .changeSequence,
            )

            store.refreshAsset(PROFILE_ID, asset(3, "Direct edit"))
            assertEquals(0, store.mergeCatalogPage(PROFILE_ID, AssetList(listOf(asset(2, "Stale catalog")))))
            assertEquals(
                "Direct edit",
                store
                    .observeAssets(PROFILE_ID)
                    .first()
                    .single()
                    .title,
            )

            val inconsistent =
                runCatching {
                    store.mergeCatalogPage(PROFILE_ID, AssetList(listOf(asset(3, "Changed without version"))))
                }
            assertTrue(inconsistent.isFailure)
            assertEquals(
                "Direct edit",
                store
                    .observeAssets(PROFILE_ID)
                    .first()
                    .single()
                    .title,
            )

            store.applyPage(PROFILE_ID, null, page(deletion(4), "cursor-4"), 104)
            assertEquals(0, store.mergeCatalogPage(PROFILE_ID, AssetList(listOf(asset(3, "Deleted asset")))))
            assertTrue(store.observeAssets(PROFILE_ID).first().isEmpty())
            assertEquals(4L, database.incrementalSyncDao().findTombstone(PROFILE_ID.value, ASSET_ID)?.version)
            assertEquals("cursor-4", store.checkpoint(PROFILE_ID)?.cursor)
        }

    private fun page(
        change: SyncChange,
        cursor: String,
    ): SyncChangeList = SyncChangeList(listOf(change), cursor, false)

    private fun upsert(
        sequence: Long,
        title: String,
    ): SyncChange =
        SyncChange(
            sequence = sequence,
            entityType = "asset",
            entityId = ASSET_ID,
            operation = "upsert",
            entityVersion = sequence,
            changedAt = NOW,
            asset =
                SyncAssetSnapshot(
                    id = ASSET_ID,
                    collectionId = null,
                    title = title,
                    language = "en-US",
                    status = "ready",
                    durationMillis = 1_000,
                    version = sequence,
                    createdAt = NOW,
                    updatedAt = NOW,
                    trashedAt = null,
                ),
        )

    private fun deletion(sequence: Long): SyncChange =
        SyncChange(
            sequence = sequence,
            entityType = "asset",
            entityId = ASSET_ID,
            operation = "delete",
            entityVersion = sequence,
            changedAt = NOW,
            asset = null,
        )

    private fun asset(
        version: Long,
        title: String,
    ): Asset =
        Asset(
            id = ASSET_ID,
            workspaceId = "00000000-0000-4000-8000-000000000003",
            collectionId = null,
            title = title,
            language = "en-US",
            status = "ready",
            durationMillis = 1_000,
            version = version,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun profile(id: ServerProfileId): ServerProfile =
        ServerProfile.create(
            id = id,
            name = "Incremental sync test",
            baseUrl = "https://example.test",
            authenticationMode = AuthenticationMode.LOCAL_SESSION,
            defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
            defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
            customCaPem = null,
            certificateFingerprint = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )

    private companion object {
        val PROFILE_ID = ServerProfileId.parse("00000000-0000-4000-8000-000000000001")
        const val ASSET_ID = "00000000-0000-4000-8000-000000000002"
        const val NOW = "2026-07-17T21:00:00Z"
    }
}
