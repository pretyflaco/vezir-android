package com.vezir.android.net

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vezir.android.MainActivity
import com.vezir.android.R
import com.vezir.android.data.Prefs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker that checks for sessions needing labeling
 * and fires a notification when new ones appear.
 *
 * Runs every 15 minutes via WorkManager. Only fires a notification for
 * sessions it hasn't already notified about (tracked in regular
 * SharedPreferences — not encrypted, since session IDs aren't secret).
 */
class LabelCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LabelCheckWorker"
        private const val WORK_NAME = "vezir-label-check"
        private const val NOTIF_CHANNEL = "vezir-labeling"
        private const val NOTIF_ID = 0x4C42  // "LB"
        private const val PREF_FILE = "vezir_label_notif"
        private const val KEY_NOTIFIED_IDS = "notified_ids"          // legacy set
        private const val KEY_NOTIFIED_JSON = "notified_ids_json"    // ordered list

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<LabelCheckWorker>(
                15, TimeUnit.MINUTES,
            )
                // v0.8.0: don't wake up offline just to burn the health-probe
                // timeouts and churn Result.retry().
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // UPDATE (not KEEP) so devices that enrolled the periodic work
                // before the constraint existed pick it up on app update.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /**
         * Append newly-notified ids, keeping insertion order and evicting the
         * OLDEST beyond [cap].  (v0.8.0: the previous StringSet storage had
         * unspecified iteration order, so eviction was arbitrary — recently
         * notified sessions could be dropped and re-notified.)
         */
        internal fun appendNotified(
            existing: List<String>,
            new: List<String>,
            cap: Int = 200,
        ): List<String> {
            val merged = (existing + new.filter { it !in existing })
            return if (merged.size > cap) merged.takeLast(cap) else merged
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(NOTIF_CHANNEL) != null) return
            val ch = NotificationChannel(
                NOTIF_CHANNEL,
                "Speaker labeling",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifies when a recording needs speaker labeling."
            }
            nm.createNotificationChannel(ch)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        val cred = prefs.activeCredential()
        if (cred == null) {
            return Result.success()  // not enrolled
        }

        val api = ResilientApi(
            cred.url, cred.altUrls, cred.token, cred.id, cred.caPem,
        )
        val result = api.execute { it.getSessions(limit = 20) }
        if (result !is SessionApi.Result.Ok) {
            Log.w(TAG, "failed to fetch sessions: $result")
            return Result.retry()
        }

        val needsLabeling = result.data.filter { it.status == "needs_labeling" }
        if (needsLabeling.isEmpty()) return Result.success()

        // Check which ones we've already notified about.  v0.8.0: stored as
        // an ordered JSON list (StringSet iteration order is unspecified, so
        // the 200-cap eviction was arbitrary).  Legacy set is migrated on
        // first read.
        val notifPrefs = applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val listSerializer = ListSerializer(String.serializer())
        val alreadyNotified: List<String> =
            notifPrefs.getString(KEY_NOTIFIED_JSON, null)?.let { raw ->
                runCatching { Json.decodeFromString(listSerializer, raw) }.getOrNull()
            } ?: notifPrefs.getStringSet(KEY_NOTIFIED_IDS, emptySet()).orEmpty().toList()
        val newSessions = needsLabeling.filter { it.id !in alreadyNotified }
        if (newSessions.isEmpty()) return Result.success()

        // Fire notification.
        ensureChannel(applicationContext)

        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            return Result.success()  // can't post; don't crash
        }

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            applicationContext, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val count = newSessions.size
        val title = if (count == 1) "Speaker labeling needed"
            else "$count sessions need labeling"
        val text = newSessions.joinToString(", ") { it.title ?: it.id.takeLast(6) }

        val notif = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, notif)

        // Track notified IDs (ordered; oldest evicted beyond the cap).
        val capped = appendNotified(alreadyNotified, newSessions.map { it.id })
        notifPrefs.edit()
            .putString(KEY_NOTIFIED_JSON, Json.encodeToString(listSerializer, capped))
            .remove(KEY_NOTIFIED_IDS)  // drop the legacy unordered set
            .apply()

        return Result.success()
    }
}
