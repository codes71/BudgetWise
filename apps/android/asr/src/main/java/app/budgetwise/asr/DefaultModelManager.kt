package app.budgetwise.asr

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import app.budgetwise.asr.download.ModelDownloadWorker
import app.budgetwise.asr.download.ModelStore
import app.budgetwise.asr.download.StorageCheck
import app.budgetwise.asr.engine.VoiceFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DefaultModelManager(
    context: Context,
    private val workManager: WorkManager,
    private val storage: StorageCheck = StorageCheck(context),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ModelManager {

    private val context = context.applicationContext

    /** A failure raised by this class itself (no work exists to carry it). */
    private val localFailure = MutableStateFlow<String?>(null)

    private val _state = MutableStateFlow(if (files() != null) ModelState.Ready(dir()) else ModelState.Missing)
    override val state: StateFlow<ModelState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE_NAME), localFailure) { infos, local ->
                if (local != null) ModelState.Failed(local) else stateFor(infos)
            }.onEach { _state.value = it }.collect {}
        }
    }

    override suspend fun ensureDownloaded(allowMetered: Boolean) {
        localFailure.value = null
        if (files() != null) return
        val remaining = withContext(Dispatchers.IO) {
            ModelStore.deleteStale(context)
            VoicePack.downloadBytes - ModelStore.bytesOnDisk(context)
        }.coerceAtLeast(0L)
        if (!storage.hasRoomFor(remaining)) {
            localFailure.value = "Not enough free storage to download the ${VoicePack.DISPLAY_NAME}"
            return
        }
        val network = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_PACK to VoicePack.VERSION))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        val name = ModelDownloadWorker.UNIQUE_NAME
        val pending = workManager.getWorkInfosForUniqueWorkFlow(name).first().lastOrNull { !it.state.isFinished }
        val policy = policyFor(pending?.state, pending?.constraints?.requiredNetworkType, network)
        workManager.enqueueUniqueWork(name, policy, request)
    }

    override suspend fun delete() {
        workManager.cancelUniqueWork(ModelDownloadWorker.UNIQUE_NAME).await()
        withContext(Dispatchers.IO) { ModelStore.packRoot(context).deleteRecursively() }
        localFailure.value = null
        _state.value = ModelState.Missing
    }

    override fun files(): VoiceFiles? = ModelStore.verified(context)

    private fun dir() = ModelStore.dir(context).path

    private fun stateFor(infos: List<WorkInfo>): ModelState {
        if (files() != null) return ModelState.Ready(dir())
        val mapped = mapWork(infos)
        // Finished records are stale once the pack's files were deleted.
        val finished = mapped is ModelState.Failed || infos.lastOrNull()?.state?.isFinished == true
        if (finished && !ModelStore.packRoot(context).exists()) return ModelState.Missing
        return mapped
    }

    companion object {
        /**
         * Waiting work keeps its network constraint, so a request with a different network type replaces it
         * (for example Wi-Fi only work waiting on a phone that left Wi-Fi). Replacing is cheap because the
         * worker leaves the .part file and the new run resumes it. Running work is never replaced: WorkManager
         * stops it cooperatively, so a replacement could start while the old run still writes the .part file.
         */
        internal fun policyFor(
            pendingState: WorkInfo.State?,
            pendingNetwork: NetworkType?,
            requested: NetworkType,
        ): ExistingWorkPolicy {
            val waiting = pendingState == WorkInfo.State.ENQUEUED || pendingState == WorkInfo.State.BLOCKED
            return if (waiting && pendingNetwork != requested) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        }

        /** Maps the unique work's records to a state, ignoring file checks. */
        internal fun mapWork(infos: List<WorkInfo>): ModelState {
            val info = infos.lastOrNull() ?: return ModelState.Missing
            return when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING ->
                    ModelState.Downloading(info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, 0f))
                WorkInfo.State.FAILED ->
                    ModelState.Failed(info.outputData.getString(ModelDownloadWorker.KEY_REASON) ?: "download failed")
                WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> ModelState.Missing
            }
        }
    }
}
