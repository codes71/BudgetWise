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
import app.budgetwise.asr.engine.ModelFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultModelManager(
    context: Context,
    private val workManager: WorkManager,
    private val storage: StorageCheck = StorageCheck(context),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ModelManager {

    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _activeVariant = MutableStateFlow(
        prefs.getString(KEY_ACTIVE, null)?.let { name -> RajVariant.entries.firstOrNull { it.name == name } },
    )
    override val activeVariant: StateFlow<RajVariant?> = _activeVariant.asStateFlow()

    /** Failures raised by this class itself (no work exists to carry them), keyed by variant. */
    private val localFailure = MutableStateFlow<Pair<RajVariant, String>?>(null)

    private val _state = MutableStateFlow(
        _activeVariant.value?.let { if (filesFor(it) != null) ModelState.Ready(dirOf(it)) else ModelState.Missing }
            ?: ModelState.Missing,
    )
    override val state: StateFlow<ModelState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(
                _activeVariant.flatMapLatest { v ->
                    if (v == null) flowOf(null)
                    else workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.uniqueName(v)).map { v to it }
                },
                localFailure,
            ) { work, local ->
                val (v, infos) = work ?: return@combine ModelState.Missing
                if (local != null && local.first == v) ModelState.Failed(local.second) else stateFor(v, infos)
            }.onEach { _state.value = it }.collect {}
        }
    }

    override suspend fun ensureDownloaded(variant: RajVariant, allowMetered: Boolean) {
        setActive(variant)
        if (filesFor(variant) != null) return
        val remaining = (variant.downloadBytes - withContext(Dispatchers.IO) { ModelStore.bytesOnDisk(context, variant) })
            .coerceAtLeast(0L)
        if (!storage.hasRoomFor(remaining)) {
            localFailure.value = variant to "Not enough free storage to download ${variant.displayName}"
            return
        }
        localFailure.value = null
        val network = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_VARIANT to variant.name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        val name = ModelDownloadWorker.uniqueName(variant)
        val pending = workManager.getWorkInfosForUniqueWorkFlow(name).first().lastOrNull { !it.state.isFinished }
        val policy = policyFor(pending?.state, pending?.constraints?.requiredNetworkType, network)
        workManager.enqueueUniqueWork(name, policy, request)
    }

    override suspend fun delete(variant: RajVariant) {
        workManager.cancelUniqueWork(ModelDownloadWorker.uniqueName(variant)).await()
        withContext(Dispatchers.IO) { ModelStore.variantRoot(context, variant).deleteRecursively() }
        if (localFailure.value?.first == variant) localFailure.value = null
        if (_activeVariant.value == variant) _state.value = ModelState.Missing
    }

    override fun filesFor(variant: RajVariant): ModelFiles? = ModelStore.verified(context, variant)

    private fun setActive(variant: RajVariant) {
        prefs.edit().putString(KEY_ACTIVE, variant.name).apply()
        localFailure.value = null
        _activeVariant.value = variant
    }

    private fun dirOf(v: RajVariant) = ModelStore.dir(context, v).path

    private fun stateFor(v: RajVariant, infos: List<WorkInfo>): ModelState {
        if (filesFor(v) != null) return ModelState.Ready(dirOf(v))
        val mapped = mapWork(infos)
        // Finished records are stale once the variant's files were deleted.
        val finished = mapped is ModelState.Failed || infos.lastOrNull()?.state?.isFinished == true
        if (finished && !ModelStore.variantRoot(context, v).exists()) return ModelState.Missing
        return mapped
    }

    companion object {
        private const val PREFS = "raj_model_manager"
        private const val KEY_ACTIVE = "active_variant"

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
