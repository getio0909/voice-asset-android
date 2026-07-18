package com.voiceasset.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.voiceasset.android.VoiceAssetApplication
import com.voiceasset.android.data.IncrementalSyncCursorConflictException
import com.voiceasset.android.security.CredentialStoreException
import com.voiceasset.android.security.ServerSessionAuthenticationRequiredException
import com.voiceasset.core.api.VoiceAssetApiException
import com.voiceasset.core.api.VoiceAssetConnectionException
import com.voiceasset.core.api.VoiceAssetProtocolException
import com.voiceasset.core.api.VoiceAssetTlsException
import com.voiceasset.core.api.requireAndroidSyncCompatibility
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class IncrementalSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val profileId =
                inputData
                    .getString(SERVER_PROFILE_ID)
                    ?.let { value -> runCatching { ServerProfileId.parse(value) }.getOrNull() }
                    ?: return@withContext failure("invalid_work_input")
            val application = applicationContext as VoiceAssetApplication
            val container = application.container
            val profile = container.serverProfiles.find(profileId) ?: return@withContext Result.success()
            val api =
                try {
                    application.serverSessions.createApi(profile)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: ServerSessionAuthenticationRequiredException) {
                    return@withContext failure("authentication_required")
                } catch (_: CredentialStoreException) {
                    return@withContext failure("authentication_required")
                } catch (exception: VoiceAssetApiException) {
                    return@withContext if (exception.statusCode == 429 || exception.statusCode >= 500) {
                        Result.retry()
                    } else {
                        failure(if (exception.statusCode == 401) "authentication_required" else exception.code)
                    }
                } catch (_: VoiceAssetConnectionException) {
                    return@withContext Result.retry()
                } catch (_: VoiceAssetTlsException) {
                    return@withContext failure("tls_verification_failed")
                } catch (_: VoiceAssetProtocolException) {
                    return@withContext failure("protocol_mismatch")
                } catch (_: IllegalArgumentException) {
                    return@withContext failure("tls_configuration_invalid")
                }
            try {
                val capabilities = api.getCapabilities()
                capabilities.requireAndroidSyncCompatibility()
                if (INCREMENTAL_SYNC_FEATURE !in capabilities.features) {
                    val catalog =
                        AssetCatalogPuller(
                            fetchPage = api::listAssets,
                            store = container.incrementalSync,
                        ).pull(profileId)
                    return@withContext Result.success(
                        workDataOf(
                            SYNC_SUPPORTED to false,
                            CATALOG_BOOTSTRAPPED to true,
                            PAGES_APPLIED to catalog.pagesApplied,
                            ASSETS_SEEN to catalog.assetsSeen,
                            ASSETS_MERGED to catalog.assetsMerged,
                        ),
                    )
                }
                val result =
                    IncrementalSyncPuller(
                        fetchPage = api::listSyncChanges,
                        store = container.incrementalSync,
                    ).pull(profileId)
                if (result.hasMore) {
                    Result.retry()
                } else {
                    Result.success(
                        workDataOf(
                            SYNC_SUPPORTED to true,
                            PAGES_APPLIED to result.pagesApplied,
                            CHANGES_APPLIED to result.changesApplied,
                        ),
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: VoiceAssetApiException) {
                if (exception.statusCode == 429 || exception.statusCode >= 500) {
                    Result.retry()
                } else {
                    failure(if (exception.statusCode == 401) "authentication_required" else exception.code)
                }
            } catch (_: VoiceAssetConnectionException) {
                Result.retry()
            } catch (_: IncrementalSyncCursorConflictException) {
                Result.retry()
            } catch (_: VoiceAssetTlsException) {
                failure("tls_verification_failed")
            } catch (_: VoiceAssetProtocolException) {
                failure("protocol_mismatch")
            } catch (_: IllegalArgumentException) {
                failure("protocol_mismatch")
            }
        }

    private fun failure(code: String): Result = Result.failure(workDataOf(ERROR_CODE to code.safeSyncErrorCode()))

    companion object {
        const val SERVER_PROFILE_ID = "server_profile_id"
        const val ERROR_CODE = "error_code"
        const val SYNC_SUPPORTED = "sync_supported"
        const val CATALOG_BOOTSTRAPPED = "catalog_bootstrapped"
        const val PAGES_APPLIED = "pages_applied"
        const val CHANGES_APPLIED = "changes_applied"
        const val ASSETS_SEEN = "assets_seen"
        const val ASSETS_MERGED = "assets_merged"
        private const val INCREMENTAL_SYNC_FEATURE = "incremental_sync"
        private const val MIN_BACKOFF_SECONDS = 30L

        internal fun oneTimeRequest(serverProfileId: ServerProfileId): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<IncrementalSyncWorker>()
                .setInputData(workDataOf(SERVER_PROFILE_ID to serverProfileId.value))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(workTag(serverProfileId))
                .build()

        fun workTag(serverProfileId: ServerProfileId): String = "voiceasset-incremental-sync-${serverProfileId.value}"
    }
}

private fun String.safeSyncErrorCode(): String =
    lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .take(100)
        .takeIf { value -> value.matches(Regex("^[a-z][a-z0-9_]{0,99}$")) }
        ?: "sync_error"
