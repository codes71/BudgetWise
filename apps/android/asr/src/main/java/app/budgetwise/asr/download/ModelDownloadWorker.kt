package app.budgetwise.asr.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.budgetwise.asr.ModelFile
import app.budgetwise.asr.R
import app.budgetwise.asr.VoicePack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Work enqueued by an older build names another pack (or a Raj variant); this build cannot fetch it.
        if (inputData.getString(KEY_PACK) != VoicePack.VERSION) return Result.failure(failure("unknown voice pack"))
        val dir = ModelStore.dir(applicationContext)
        dir.mkdirs()
        showForeground(0f)

        val total = VoicePack.downloadBytes.toDouble()
        val current = AtomicLong(0)
        return try {
            coroutineScope {
                val reporter = launch {
                    while (true) {
                        delay(PROGRESS_INTERVAL_MS)
                        val fraction = (current.get() / total).toFloat().coerceIn(0f, 1f)
                        setProgress(workDataOf(KEY_PROGRESS to fraction))
                        showForeground(fraction)
                    }
                }
                var base = 0L
                for (file in VoicePack.files) {
                    val dest = ModelStore.fileFor(dir, file)
                    // A renamed file already passed size and sha256 in ModelDownloader.
                    if (!(dest.isFile && dest.length() == file.bytes)) {
                        val b = base
                        fetcher(file, dest) { onDisk -> current.set(b + onDisk) }
                    }
                    base += file.bytes
                    current.set(base)
                }
                reporter.cancel()
                // Marker is written last: its presence means every file is complete and verified.
                ModelStore.writeMarker(dir)
            }
            setProgress(workDataOf(KEY_PROGRESS to 1f))
            notifyReady()
            Result.success()
        } catch (e: DownloadException.NotConfigured) {
            Result.failure(failure("voice model host not configured (${e.file})"))
        } catch (e: DownloadException.HashMismatch) {
            Result.failure(failure("download corrupted (hash mismatch for ${e.file})"))
        } catch (e: DownloadException.Http) {
            if (e.code >= 500 && runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(failure("http error ${e.code}"))
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(failure("network error: ${e.message}"))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0f, indeterminate = true)

    private suspend fun showForeground(fraction: Float) {
        // Starting a foreground service can be refused (background start limits); the download still runs.
        try {
            setForeground(foregroundInfo(fraction, indeterminate = false))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun foregroundInfo(fraction: Float, indeterminate: Boolean): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        channel(manager, CHANNEL_ID, R.string.asr_channel_download, NotificationManager.IMPORTANCE_LOW)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.asr_downloading))
            .setProgress(100, (fraction * 100).toInt(), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /**
     * "Voice is ready", which opens the app. Its own channel, at default importance, so it is seen even when the
     * progress channel is silenced. Skipped when the user has turned notifications off (or not allowed them).
     */
    private fun notifyReady() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        channel(manager, READY_CHANNEL_ID, R.string.asr_channel_ready, NotificationManager.IMPORTANCE_DEFAULT)
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val open = launch?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(applicationContext, READY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(applicationContext.getString(R.string.asr_ready_title))
            .setContentText(applicationContext.getString(R.string.asr_ready_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(READY_NOTIFICATION_ID, notification)
    }

    private fun channel(manager: NotificationManager, id: String, name: Int, importance: Int) {
        if (Build.VERSION.SDK_INT >= 26) {
            // Created again each time, which also renames it when the app's language changed.
            manager.createNotificationChannel(NotificationChannel(id, applicationContext.getString(name), importance))
        }
    }

    private fun failure(reason: String): Data = workDataOf(KEY_REASON to reason)

    companion object {
        const val KEY_PACK = "pack"
        const val KEY_PROGRESS = "progress"
        const val KEY_REASON = "reason"
        const val CHANNEL_ID = "voice_model_download"
        const val READY_CHANNEL_ID = "voice_ready"
        private const val NOTIFICATION_ID = 0x5a17
        internal const val READY_NOTIFICATION_ID = 0x5a18
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val MAX_ATTEMPTS = 5

        const val UNIQUE_NAME = "voice-pack-${VoicePack.VERSION}"

        /** Test seam: replace to avoid the network. */
        @Volatile
        internal var fetcher: suspend (ModelFile, File, (Long) -> Unit) -> Unit = { f, dest, onBytes ->
            ModelDownloader(OkHttpClient(), ModelSource()).fetch(f, dest, onBytes)
        }
    }
}
