package com.voiceasset.android.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `remote_assets` (
                    `server_profile_id` TEXT NOT NULL,
                    `asset_id` TEXT NOT NULL,
                    `collection_id` TEXT,
                    `title` TEXT NOT NULL,
                    `language` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `duration_ms` INTEGER,
                    `version` INTEGER NOT NULL,
                    `change_sequence` INTEGER NOT NULL,
                    `created_at_epoch_millis` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    `trashed_at_epoch_millis` INTEGER,
                    PRIMARY KEY(`server_profile_id`, `asset_id`),
                    FOREIGN KEY(`server_profile_id`) REFERENCES `server_profiles`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_remote_assets_server_profile_id_updated_at_epoch_millis`
                ON `remote_assets` (`server_profile_id`, `updated_at_epoch_millis`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `remote_asset_tombstones` (
                    `server_profile_id` TEXT NOT NULL,
                    `asset_id` TEXT NOT NULL,
                    `version` INTEGER NOT NULL,
                    `change_sequence` INTEGER NOT NULL,
                    `deleted_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`server_profile_id`, `asset_id`),
                    FOREIGN KEY(`server_profile_id`) REFERENCES `server_profiles`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_remote_asset_tombstones_server_profile_id_deleted_at_epoch_millis`
                ON `remote_asset_tombstones` (`server_profile_id`, `deleted_at_epoch_millis`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `incremental_sync_cursors` (
                    `server_profile_id` TEXT NOT NULL,
                    `cursor` TEXT NOT NULL,
                    `last_sequence` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`server_profile_id`),
                    FOREIGN KEY(`server_profile_id`) REFERENCES `server_profiles`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
    }

val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `sync_tasks` ADD COLUMN `manual_retry_generation` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `recordings` ADD COLUMN `upload_policy_override` TEXT")
            db.execSQL("ALTER TABLE `recordings` ADD COLUMN `transcription_policy_override` TEXT")
        }
    }
