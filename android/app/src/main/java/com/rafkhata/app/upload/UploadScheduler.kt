package com.rafkhata.app.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rafkhata.app.data.db.RecordingState
import com.rafkhata.app.data.repo.RecordingRepository
import com.rafkhata.app.data.settings.SettingsStore
import java.util.concurrent.TimeUnit

/** Queues one [UploadWorker] per recording; WorkManager waits for the network and retries. */
class UploadScheduler(
    private val context: Context,
    private val settings: SettingsStore,
    private val recordings: RecordingRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Queue an upload. [ignoreWifiOnly] uploads over mobile data too ("Upload now"); [replace]
     * swaps a waiting request for this one so new network rules apply.
     */
    suspend fun enqueue(recordingId: String, ignoreWifiOnly: Boolean = false, replace: Boolean = ignoreWifiOnly) {
        val wifiOnly = !ignoreWifiOnly && settings.current().wifiOnlyUploads
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_RECORDING_ID to recordingId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        recordings.setState(recordingId, RecordingState.QUEUED)
        workManager.enqueueUniqueWork(
            workName(recordingId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Re-queue everything waiting, e.g. after sign-in or when the Wi-Fi-only setting changes. */
    suspend fun requeueAll(replace: Boolean) {
        recordings.withStates(RecordingState.QUEUED, RecordingState.UPLOADING).forEach {
            enqueue(it.id, ignoreWifiOnly = false, replace = replace)
        }
    }

    fun cancel(recordingId: String) {
        workManager.cancelUniqueWork(workName(recordingId))
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    private fun workName(recordingId: String) = "upload-$recordingId"

    private companion object {
        const val TAG = "upload"
    }
}
