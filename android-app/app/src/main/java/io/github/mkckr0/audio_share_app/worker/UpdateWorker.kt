/*
 *    Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.mkckr0.audio_share_app.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mkckr0.audio_share_app.BuildConfig
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.model.Channel
import io.github.mkckr0.audio_share_app.model.Notification
import io.github.mkckr0.audio_share_app.model.UpdateContract
import io.github.mkckr0.audio_share_app.model.UpdateManifest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class UpdateWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    private val tag = javaClass.simpleName
    private val suppressMessage by lazy { inputData.getBoolean(KEY_SUPPRESS_MESSAGE, false) }

    override suspend fun doWork(): Result {
        Log.d(tag, "Checking the Supabase update manifest")
        val httpClient = HttpClient {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        return try {
            val response = httpClient.get(UpdateContract.ENDPOINT) {
                parameter("channel", UpdateContract.CHANNEL)
                parameter("target", UpdateContract.TARGET)
                parameter("arch", UpdateContract.ARCHITECTURE)
                parameter("current_version", BuildConfig.VERSION_NAME)
            }

            when (response.status) {
                HttpStatusCode.NoContent -> {
                    showMessage(applicationContext.getString(R.string.label_no_update))
                    Result.success()
                }

                HttpStatusCode.OK -> {
                    val manifest: UpdateManifest = response.body()
                    if (!UpdateContract.isValidVersion(manifest.version) ||
                        !UpdateContract.isAllowedDownloadUrl(manifest.url) ||
                        !UpdateContract.isValidSignature(manifest.signature)
                    ) {
                        throw IllegalArgumentException("The update manifest is invalid")
                    }
                    showAvailableUpdate(manifest)
                    Result.success()
                }

                else -> throw IllegalStateException(
                    "Update endpoint returned HTTP ${response.status.value}"
                )
            }
        } catch (error: Exception) {
            Log.e(tag, "Update check failed", error)
            showMessage(applicationContext.getString(R.string.label_update_check_failed))
            if (suppressMessage) Result.retry() else Result.failure()
        } finally {
            httpClient.close()
        }
    }

    private suspend fun showAvailableUpdate(manifest: UpdateManifest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            showMessage(applicationContext.getString(R.string.label_no_post_notification_permission))
            return
        }

        val intent = Intent(applicationContext, UpdateDownloadReceiver::class.java).apply {
            putExtra(UpdateDownloadWorker.KEY_VERSION, manifest.version)
            putExtra(UpdateDownloadWorker.KEY_URL, manifest.url)
            putExtra(UpdateDownloadWorker.KEY_SIGNATURE, manifest.signature)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(applicationContext, Channel.UPDATE.id)
            .setSmallIcon(R.drawable.baseline_update)
            .setContentTitle(
                applicationContext.getString(R.string.label_has_an_update_2)
                    .format(manifest.version)
            )
            .setContentText(applicationContext.getString(R.string.label_tap_notification_1))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(Notification.UPDATE.id, notification)
        showMessage(applicationContext.getString(R.string.label_tap_notification_2))
    }

    private suspend fun showMessage(message: String) {
        if (!suppressMessage) {
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val KEY_SUPPRESS_MESSAGE = "SUPPRESS_MESSAGE"
    }
}
