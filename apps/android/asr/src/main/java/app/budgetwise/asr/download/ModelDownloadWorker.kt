package app.budgetwise.asr.download

import android.app.NotificationChannel
import android.app.NotificationManager
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
import app.budgetwise.asr.RajVariant
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
        val variant = inputData.getString(KEY_VARIANT)?.let { name -> RajVariant.entries.firstOrNull { it.name == name } }
            ?: return Result.failure(failure("unknown model variant"))
        val dir = ModelStore.dir(applicationContext, variant)
        dir.mkdirs()
        showForeground(variant, 0f)

        val total = variant.downloadBytes.toDouble()
        val current = AtomicLong(0)
        return try {
            coroutineScope {
                val reporter = launch {
                    while (true) {
                        delay(PROGRESS_INTERVAL_MS)
                        val fraction = (current.get() / total).toFloat().coerceIn(0f, 1f)
                        setProgress(workDataOf(KEY_PROGRESS to fraction))
                        showForeground(variant, fraction)
                    }
                }
                var base = 0L
                for (file in listOf(variant.model, variant.mmproj)) {
                    val dest = ModelStore.fileFor(dir, file)
                    // A renamed file already passed size and sha256 in ModelDownloader.
                    if (!(dest.isFile && dest.length() == file.bytes)) {
                        val b = base
                        fetcher(variant, file, dest) { onDisk -> current.set(b + onDisk) }
                    }
                    base += file.bytes
                    current.set(base)
                }
                reporter.cancel()
                // Marker is written last: its presence means both files are complete and verified.
                ModelStore.writeMarker(dir, variant)
            }
            setProgress(workDataOf(KEY_PROGRESS to 1f))
            Result.success()
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

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null, 0f)

    private suspend fun showForeground(variant: RajVariant, fraction: Float) {
        // Starting a foreground service can be refused (background start limits); the download still runs.
        try {
            setForeground(foregroundInfo(variant, fraction))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun foregroundInfo(variant: RajVariant?, fraction: Float): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Speech model download", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${variant?.displayName ?: "speech model"}")
            .setProgress(100, (fraction * 100).toInt(), variant == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun failure(reason: String): Data = workDataOf(KEY_REASON to reason)

    companion object {
        const val KEY_VARIANT = "variant"
        const val KEY_PROGRESS = "progress"
        const val KEY_REASON = "reason"
        const val CHANNEL_ID = "raj_model_download"
        private const val NOTIFICATION_ID = 0x5a17
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val MAX_ATTEMPTS = 5

        fun uniqueName(v: RajVariant) = "raj-model-${v.name}"

        /** Test seam: replace to avoid the network. */
        @Volatile
        internal var fetcher: suspend (RajVariant, ModelFile, File, (Long) -> Unit) -> Unit = { v, f, dest, onBytes ->
            ModelDownloader(OkHttpClient(), ModelSource()).fetch(v, f, dest, onBytes)
        }
    }
}
