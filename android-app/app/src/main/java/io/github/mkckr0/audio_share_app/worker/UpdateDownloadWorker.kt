package io.github.mkckr0.audio_share_app.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mkckr0.audio_share_app.BuildConfig
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.model.Channel
import io.github.mkckr0.audio_share_app.model.Notification
import io.github.mkckr0.audio_share_app.model.UpdateContract
import io.github.mkckr0.audio_share_app.model.UpdateVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateDownloadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val version = inputData.getString(KEY_VERSION)
        val url = inputData.getString(KEY_URL)
        val signature = inputData.getString(KEY_SIGNATURE)
        if (version == null || url == null || signature == null ||
            !UpdateContract.isValidVersion(version) ||
            !UpdateContract.isAllowedDownloadUrl(url) ||
            !UpdateContract.isValidSignature(signature)
        ) {
            showFailureNotification()
            return@withContext Result.failure()
        }

        val updateDirectory = File(applicationContext.cacheDir, "updates")
        val temporaryFile = File(updateDirectory, "AudioShare-Android-v$version.apk.part")
        val apkFile = File(updateDirectory, "AudioShare-Android-v$version.apk")
        try {
            updateDirectory.mkdirs()
            temporaryFile.delete()
            apkFile.delete()
            download(url, temporaryFile)
            if (!UpdateVerifier.verify(temporaryFile, signature)) {
                throw SecurityException("The APK updater signature is invalid")
            }
            if (!temporaryFile.renameTo(apkFile)) {
                temporaryFile.copyTo(apkFile, overwrite = true)
                temporaryFile.delete()
            }
            showInstallNotification(version, apkFile)
            Result.success()
        } catch (_: Exception) {
            temporaryFile.delete()
            apkFile.delete()
            showFailureNotification()
            Result.failure()
        }
    }

    private fun download(initialUrl: String, destination: File) {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!UpdateContract.isAllowedDownloadUrl(currentUrl)) {
                throw SecurityException("The update download URL is not allowed")
            }
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", "AudioShare-Android/${BuildConfig.VERSION_NAME}")
                val status = connection.responseCode
                if (status in 300..399) {
                    if (redirectCount == MAX_REDIRECTS) throw IllegalStateException("Too many redirects")
                    currentUrl = URL(URL(currentUrl), connection.getHeaderField("Location")).toString()
                    return@repeat
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("APK download returned HTTP $status")
                }
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_APK_BYTES) throw IllegalStateException("APK is too large")
                connection.inputStream.buffered().use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_APK_BYTES) throw IllegalStateException("APK is too large")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (destination.length() == 0L) throw IllegalStateException("APK download is empty")
                return
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("APK download failed")
    }

    private fun showInstallNotification(version: String, apkFile: File) {
        if (!canPostNotifications()) return
        val apkUri = FileProvider.getUriForFile(
            applicationContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            1,
            installIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(applicationContext, Channel.UPDATE.id)
            .setSmallIcon(R.drawable.baseline_update)
            .setContentTitle(applicationContext.getString(R.string.label_update_ready).format(version))
            .setContentText(applicationContext.getString(R.string.label_tap_to_install_update))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext)
            .notify(Notification.UPDATE.id, notification)
    }

    private fun showFailureNotification() {
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(applicationContext, Channel.UPDATE.id)
            .setSmallIcon(R.drawable.baseline_update)
            .setContentTitle(applicationContext.getString(R.string.label_update_download_failed))
            .setContentText(applicationContext.getString(R.string.label_update_signature_required))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext)
            .notify(Notification.UPDATE.id, notification)
    }

    private fun canPostNotifications(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ActivityCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val WORK_NAME = "download_update"
        const val KEY_VERSION = "VERSION"
        const val KEY_URL = "URL"
        const val KEY_SIGNATURE = "SIGNATURE"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_APK_BYTES = 200L * 1024L * 1024L
    }
}
