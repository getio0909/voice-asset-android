package com.voiceasset.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.voiceasset.core.model.ServerProfileId
import java.util.concurrent.TimeUnit

interface RemoteAssetSyncScheduler {
    fun schedule(serverProfileId: ServerProfileId)

    fun refresh(serverProfileId: ServerProfileId)

    companion object {
        val NONE =
            object : RemoteAssetSyncScheduler {
                override fun schedule(serverProfileId: ServerProfileId) = Unit

                override fun refresh(serverProfileId: ServerProfileId) = Unit
            }
    }
}

class IncrementalSyncScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : RemoteAssetSyncScheduler {
    override fun schedule(serverProfileId: ServerProfileId) {
        refresh(serverProfileId)
        schedulePeriodic(serverProfileId)
    }

    override fun refresh(serverProfileId: ServerProfileId) {
        workManager.enqueueUniqueWork(
            immediateWorkName(serverProfileId),
            ExistingWorkPolicy.KEEP,
            IncrementalSyncWorker.oneTimeRequest(serverProfileId),
        )
    }

    private fun schedulePeriodic(serverProfileId: ServerProfileId) {
        val periodic =
            PeriodicWorkRequestBuilder<IncrementalSyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(IncrementalSyncWorker.SERVER_PROFILE_ID to serverProfileId.value))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(IncrementalSyncWorker.workTag(serverProfileId))
                .build()
        workManager.enqueueUniquePeriodicWork(
            periodicWorkName(serverProfileId),
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }

    fun cancel(serverProfileId: ServerProfileId) {
        workManager.cancelUniqueWork(immediateWorkName(serverProfileId))
        workManager.cancelUniqueWork(periodicWorkName(serverProfileId))
    }

    companion object {
        private const val PERIODIC_INTERVAL_MINUTES = 15L
        private const val MIN_BACKOFF_SECONDS = 30L

        fun immediateWorkName(serverProfileId: ServerProfileId): String = "voiceasset-incremental-sync-now-${serverProfileId.value}"

        fun periodicWorkName(serverProfileId: ServerProfileId): String = "voiceasset-incremental-sync-periodic-${serverProfileId.value}"
    }
}
