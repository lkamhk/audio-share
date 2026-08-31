package io.github.mkckr0.audio_share_app.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val version = intent.getStringExtra(UpdateDownloadWorker.KEY_VERSION) ?: return
        val url = intent.getStringExtra(UpdateDownloadWorker.KEY_URL) ?: return
        val signature = intent.getStringExtra(UpdateDownloadWorker.KEY_SIGNATURE) ?: return
        val inputData = Data.Builder()
            .putString(UpdateDownloadWorker.KEY_VERSION, version)
            .putString(UpdateDownloadWorker.KEY_URL, url)
            .putString(UpdateDownloadWorker.KEY_SIGNATURE, signature)
            .build()
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UpdateDownloadWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
