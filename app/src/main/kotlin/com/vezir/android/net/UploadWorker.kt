package com.vezir.android.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vezir.android.R
import com.vezir.android.data.Prefs
import java.util.concurrent.TimeUnit

/**
 * Foreground [CoroutineWorker] that owns the actual upload (v0.8.0).
 *
 * Previously the upload ran in a process-wide coroutine scope with no
 * foreground service and no WorkManager: backgrounding the app (Doze,
 * app-standby, LMK) could kill a multi-minute transfer silently, with no
 * retry and no user-visible error — and the tus `upload_id` wasn't
 * persisted, so the next attempt restarted from byte 0.
 *
 * Now:
 *  * runs as foreground work (dataSync) with an upload notification, so
 *    the transfer survives backgrounding;
 *  * persists the upload session in [UploadStateStore] the moment it is
 *    created, and resumes from the server's offset on any retry —
 *    including a WorkManager reschedule after process death;
 *  * reads credentials fresh from [Prefs] on every run (and per request
 *    via the token provider), so a token rotated mid-upload is used
 *    immediately;
 *  * network-constrained with exponential backoff; transient failures
 *    return [Result.retry], contract failures (4xx) fail fast.
 *
 * UI state still flows through [UploadController]'s snapshot; the worker
 * writes to it since both live in the same process.  If the process was
 * restarted (headless retry), there's no UI to update — the upload still
 * completes, and the sessions list shows the result on next launch.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo())

        val contentUri = inputData.getString(KEY_URI)?.let(Uri::parse)
            ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "recording.ogg"
        val title = inputData.getString(KEY_TITLE)
        val summaryPreset = inputData.getString(KEY_PRESET)
        val autoLabel = inputData.getBoolean(KEY_AUTO_LABEL, true)
        val sync = inputData.getBoolean(KEY_SYNC, true)
        val personal = inputData.getBoolean(KEY_PERSONAL, false)
        val preferredUrl = inputData.getString(KEY_BASE_URL)

        // Credentials are NEVER passed through WorkManager's Data (its DB
        // is plaintext on disk); read them fresh from encrypted prefs.
        val prefs = Prefs.get(applicationContext)
        val cred = prefs.activeCredential()
            ?: return fail("not signed in")
        val baseUrl = preferredUrl ?: cred.url
        val tokenProvider: () -> String = {
            prefs.activeCredential()?.token ?: cred.token
        }

        val stateStore = UploadStateStore(applicationContext)
        val existing = stateStore.get(contentUri.toString(), baseUrl)

        val resumable = ResumableUploader(
            baseUrl, tokenProvider, cred.id,
            applicationContext.contentResolver, cred.caPem,
        )
        val progress = ResumableUploader.Progress { sent, total ->
            UploadController.publishProgress(sent, total)
        }
        val onRetry = ResumableUploader.OnRetry { attempt, max, _ ->
            UploadController.publishRetry(attempt + 1, max)
        }

        if (resumable.isSupported()) {
            when (val o = resumable.upload(
                contentUri, fileName, title, summaryPreset,
                autoLabel, sync, personal,
                progress = progress, onRetry = onRetry,
                existingUploadId = existing?.uploadId,
                onSession = { id ->
                    stateStore.put(contentUri.toString(), id, baseUrl)
                },
            )) {
                is ResumableUploader.Outcome.Success -> {
                    stateStore.clear()
                    UploadController.publishSuccess(
                        baseUrl, tokenProvider(), cred.id,
                        o.sessionId, o.bytes, cred.caPem,
                    )
                    return Result.success(workDataOf(OUT_SESSION_ID to o.sessionId))
                }
                is ResumableUploader.Outcome.HttpError -> {
                    stateStore.clear()
                    return fail(
                        if (o.code == 401) UploadController.SESSION_EXPIRED_MESSAGE
                        else "HTTP ${o.code}: ${o.message}",
                    )
                }
                is ResumableUploader.Outcome.Failed ->
                    return retryOrFail(o.cause.message ?: o.cause.toString())
                is ResumableUploader.Outcome.Unsupported -> {
                    // fall through to the legacy one-shot path below
                }
            }
        }

        val uploader = Uploader(
            baseUrl, tokenProvider, cred.id,
            applicationContext.contentResolver, cred.caPem,
        )
        return when (val outcome = uploader.upload(
            contentUri = contentUri,
            fileName = fileName,
            title = title,
            summaryPreset = summaryPreset,
            autoLabel = autoLabel,
            sync = sync,
            personal = personal,
            progress = { sent, total -> UploadController.publishProgress(sent, total) },
            onRetry = { attempt, max, _ ->
                UploadController.publishRetry(attempt + 1, max, restarted = true)
            },
        )) {
            is Uploader.Outcome.Success -> {
                UploadController.publishSuccess(
                    baseUrl, tokenProvider(), cred.id,
                    outcome.response.session_id,
                    outcome.response.bytes, cred.caPem,
                )
                Result.success(
                    workDataOf(OUT_SESSION_ID to outcome.response.session_id),
                )
            }
            is Uploader.Outcome.HttpError -> fail(
                if (outcome.code == 401) UploadController.SESSION_EXPIRED_MESSAGE
                else "HTTP ${outcome.code}: ${outcome.message}",
            )
            is Uploader.Outcome.Failed ->
                retryOrFail(outcome.cause.message ?: outcome.cause.toString())
        }
    }

    private fun fail(message: String): Result {
        Log.w(TAG, "upload failed: $message")
        UploadController.publishError(message)
        return Result.failure(workDataOf(OUT_ERROR to message))
    }

    private fun retryOrFail(message: String): Result {
        // The uploader already burned its in-run attempts; let WorkManager
        // reschedule (surviving process death) a bounded number of times.
        return if (runAttemptCount < MAX_WORK_ATTEMPTS - 1) {
            Log.w(TAG, "upload transient failure (work attempt $runAttemptCount): $message; will retry")
            UploadController.publishRetry(runAttemptCount + 2, MAX_WORK_ATTEMPTS)
            Result.retry()
        } else {
            fail(message)
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        ensureChannel(applicationContext)
        val notif = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Uploading recording")
            .setContentText("Sending to the vezir server\u2026")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "VezirUploadWorker"
        private const val WORK_NAME = "vezir-upload"
        private const val NOTIF_CHANNEL = "vezir-upload"
        private const val NOTIF_ID = 0x5541 // "UA"
        private const val MAX_WORK_ATTEMPTS = 5

        private const val KEY_URI = "uri"
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_TITLE = "title"
        private const val KEY_PRESET = "preset"
        private const val KEY_AUTO_LABEL = "auto_label"
        private const val KEY_SYNC = "sync"
        private const val KEY_PERSONAL = "personal"
        private const val KEY_BASE_URL = "base_url"
        const val OUT_SESSION_ID = "session_id"
        const val OUT_ERROR = "error"

        /**
         * Enqueue (or replace) the app's single upload as unique work.
         * Credentials are read inside the worker; only non-secrets ride
         * in the input Data.
         */
        fun enqueue(
            context: Context,
            baseUrl: String,
            contentUri: Uri,
            fileName: String,
            title: String?,
            summaryPreset: String?,
            autoLabel: Boolean,
            sync: Boolean,
            personal: Boolean,
        ) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_URI to contentUri.toString(),
                        KEY_FILE_NAME to fileName,
                        KEY_TITLE to title,
                        KEY_PRESET to summaryPreset,
                        KEY_AUTO_LABEL to autoLabel,
                        KEY_SYNC to sync,
                        KEY_PERSONAL to personal,
                        KEY_BASE_URL to baseUrl,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(NOTIF_CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL,
                    "Uploads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows while a recording is uploading."
                },
            )
        }
    }
}
