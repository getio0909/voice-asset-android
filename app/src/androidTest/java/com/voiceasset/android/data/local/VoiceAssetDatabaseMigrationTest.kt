package com.voiceasset.android.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceAssetDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            VoiceAssetDatabase::class.java,
        )

    private lateinit var context: Context

    @Before
    fun removePreviousDatabase() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun removeTestDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migratesVersionOneWithoutLosingProfilesOrRetryState() {
        helper.createDatabase(DATABASE_NAME, 1).use { database ->
            database.execSQL(
                """
                INSERT INTO server_profiles (
                    id, name, origin, authentication_mode, default_upload_policy,
                    default_transcription_policy, custom_ca_pem, certificate_fingerprint,
                    created_at_epoch_millis, updated_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    PROFILE_ID,
                    "Migrated",
                    "https://example.test",
                    "LOCAL_SESSION",
                    "WIFI_ONLY",
                    "AFTER_UPLOAD",
                    1,
                    1,
                ),
            )
            database.execSQL(
                """
                INSERT INTO recordings (
                    session_id, file_name, started_at_epoch_millis, server_profile_id,
                    status, duration_millis, size_bytes, sha256, stopped_at_epoch_millis,
                    error_code, updated_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    RECORDING_ID,
                    "migrated.m4a",
                    1,
                    PROFILE_ID,
                    "SAVED",
                    1_000,
                    100,
                    "a".repeat(64),
                    2,
                    2,
                ),
            )
            database.execSQL(
                """
                INSERT INTO sync_tasks (
                    recording_session_id, server_profile_id, stage, asset_id, upload_id,
                    transcription_job_id, uploaded_bytes, total_bytes, attempt_count,
                    last_error_code, block_reason, created_at_epoch_millis, updated_at_epoch_millis
                ) VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    RECORDING_ID,
                    PROFILE_ID,
                    "FAILED",
                    ASSET_ID,
                    0,
                    100,
                    4,
                    "retry_exhausted",
                    "NONE",
                    2,
                    3,
                ),
            )
        }

        val migratedDatabase =
            helper.runMigrationsAndValidate(
                DATABASE_NAME,
                4,
                true,
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
            )
        migratedDatabase.use { database ->
            database.query("SELECT name FROM server_profiles WHERE id = ?", arrayOf(PROFILE_ID)).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Migrated", cursor.getString(0))
            }
            database
                .query(
                    """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type = 'table' AND name IN (
                        'remote_assets', 'remote_asset_tombstones', 'incremental_sync_cursors'
                    )
                    """.trimIndent(),
                ).use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(3, cursor.getInt(0))
                }
            database
                .query(
                    "SELECT upload_policy_override, transcription_policy_override FROM recordings WHERE session_id = ?",
                    arrayOf(RECORDING_ID),
                ).use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(true, cursor.isNull(0))
                    assertEquals(true, cursor.isNull(1))
                }
            database.execSQL(
                """
                INSERT INTO incremental_sync_cursors (
                    server_profile_id, cursor, last_sequence, updated_at_epoch_millis
                ) VALUES (?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(PROFILE_ID, "cursor-1", 1, 2),
            )
            database
                .query(
                    "SELECT stage, attempt_count, manual_retry_generation FROM sync_tasks WHERE recording_session_id = ?",
                    arrayOf(RECORDING_ID),
                ).use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals("FAILED", cursor.getString(0))
                    assertEquals(4, cursor.getInt(1))
                    assertEquals(0, cursor.getInt(2))
                }
            database.execSQL("PRAGMA foreign_keys = ON")
            database.execSQL("DELETE FROM server_profiles WHERE id = ?", arrayOf(PROFILE_ID))
            database.query("SELECT COUNT(*) FROM incremental_sync_cursors").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "voiceasset-migration-test.db"
        const val PROFILE_ID = "00000000-0000-4000-8000-000000000001"
        const val RECORDING_ID = "10000000-0000-4000-8000-000000000001"
        const val ASSET_ID = "20000000-0000-4000-8000-000000000001"
    }
}
