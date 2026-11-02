package app.budgetwise.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.budgetwise.asr.download.ModelDownloadWorker
import app.budgetwise.asr.download.ModelStore
import app.budgetwise.asr.download.StorageCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultModelManagerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private var roomForDownload = true
    private val requestedRoom = mutableListOf<Long>()
    private var fetchCalls = 0
    private val savedFetcher = ModelDownloadWorker.fetcher

    private val storage by lazy {
        object : StorageCheck(context) {
            override fun hasRoomFor(bytes: Long): Boolean {
                requestedRoom += bytes
                return roomForDownload
            }
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build(),
        )
        workManager = WorkManager.getInstance(context)
        ModelDownloadWorker.fetcher = { _, _, _ -> fetchCalls++ }
    }

    @After
    fun tearDown() {
        ModelDownloadWorker.fetcher = savedFetcher
        ModelStore.modelsRoot(context).deleteRecursively()
    }

    private fun manager() = DefaultModelManager(context, workManager, storage)

    /** Writes every file with the right size (sparse) and a marker, as a finished download would. */
    private fun installVerified(): File {
        val dir = ModelStore.dir(context)
        dir.mkdirs()
        for (f in VoicePack.files) {
            java.io.RandomAccessFile(ModelStore.fileFor(dir, f), "rw").use { it.setLength(f.bytes) }
        }
        ModelStore.writeMarker(dir)
        return dir
    }

    private fun <T> awaitValue(timeoutMs: Long = 5_000, block: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("condition not met in ${timeoutMs}ms")
    }

    private fun workInfos() = workManager.getWorkInfosForUniqueWork(ModelDownloadWorker.UNIQUE_NAME).get()

    @Test
    fun missingWhenNoFiles() {
        val m = manager()
        assertEquals(ModelState.Missing, m.state.value)
        assertNull(m.files())
    }

    @Test
    fun readyAfterVerifiedMarkerPresent() = runBlocking<Unit> {
        val dir = installVerified()
        val m = manager()
        m.ensureDownloaded()
        val ready = awaitValue { m.state.value as? ModelState.Ready }
        assertEquals(dir.path, ready.path)
        val files = m.files()!!
        assertEquals(File(dir, "model.int8.onnx"), files.omnilingualModel)
        assertEquals(File(dir, "vocab.json"), files.freococoVocab)
        assertTrue(workInfos().isEmpty())
        // Restart: state is Ready immediately.
        assertEquals(ModelState.Ready(dir.path), manager().state.value)
    }

    @Test
    fun missingFileIsNotVerified() {
        val dir = installVerified()
        ModelStore.fileFor(dir, VoicePack.OMNILINGUAL_TOKENS).delete()
        assertNull(manager().files())
    }

    @Test
    fun truncatedFileIsNotVerified() {
        val dir = installVerified()
        java.io.RandomAccessFile(ModelStore.fileFor(dir, VoicePack.FREOCOCO_MODEL), "rw")
            .use { it.setLength(VoicePack.FREOCOCO_MODEL.bytes - 1) }
        assertNull(manager().files())
    }

    @Test
    fun markerWithMismatchedShaIsNotVerified() {
        val dir = installVerified()
        val marker = File(dir, ModelStore.MARKER)
        marker.writeText(marker.readText().replace(VoicePack.OMNILINGUAL_MODEL.sha256, "0".repeat(64)))
        assertNull(manager().files())
    }

    @Test
    fun corruptMarkerIsNotVerifiedAndDoesNotCrash() {
        val dir = installVerified()
        File(dir, ModelStore.MARKER).writeText("{not json")
        val m = manager()
        assertNull(m.files())
        File(dir, ModelStore.MARKER).writeText("")
        assertNull(m.files())
    }

    /** Runs a download that fails with a hash mismatch and waits for the manager to show Failed. */
    private suspend fun failDownload(m: DefaultModelManager): ModelState.Failed {
        ModelDownloadWorker.fetcher = { f, _, _ -> throw app.budgetwise.asr.download.DownloadException.HashMismatch(f.path) }
        m.ensureDownloaded()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(workInfos().single().id)
        return awaitValue { m.state.value as? ModelState.Failed }
    }

    @Test
    fun restartRestoresFailedStateFromWork() = runBlocking<Unit> {
        assertTrue(failDownload(manager()).reason.contains("hash"))
        val again = manager()
        awaitValue<ModelState> { again.state.value as? ModelState.Failed }
    }

    @Test
    fun restartRestoresDownloadingStateFromWork() = runBlocking<Unit> {
        manager().ensureDownloaded()
        val again = manager()
        awaitValue<ModelState> { again.state.value as? ModelState.Downloading }
    }

    @Test
    fun enqueuesUniqueWorkUnmeteredByDefault() = runBlocking<Unit> {
        val m = manager()
        m.ensureDownloaded()
        m.ensureDownloaded()
        val infos = workInfos()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.UNMETERED, infos[0].constraints.requiredNetworkType)
        awaitValue<ModelState> { m.state.value as? ModelState.Downloading }
    }

    @Test
    fun allowMeteredUsesConnected() = runBlocking<Unit> {
        manager().ensureDownloaded(allowMetered = true)
        assertEquals(NetworkType.CONNECTED, workInfos().single().constraints.requiredNetworkType)
    }

    @Test
    fun allowMeteredUpgradesPendingUnmeteredWork() = runBlocking<Unit> {
        val m = manager()
        m.ensureDownloaded()
        assertEquals(NetworkType.UNMETERED, workInfos().single().constraints.requiredNetworkType)
        m.ensureDownloaded(allowMetered = true)
        val live = workInfos().filter { !it.state.isFinished }
        assertEquals(1, live.size)
        assertEquals(NetworkType.CONNECTED, live.single().constraints.requiredNetworkType)
    }

    @Test
    fun onlyWaitingWorkIsReplacedOnNetworkChange() {
        val connected = NetworkType.CONNECTED
        val unmetered = NetworkType.UNMETERED
        assertEquals(ExistingWorkPolicy.KEEP, DefaultModelManager.policyFor(null, null, connected))
        assertEquals(ExistingWorkPolicy.REPLACE, DefaultModelManager.policyFor(WorkInfo.State.ENQUEUED, unmetered, connected))
        assertEquals(ExistingWorkPolicy.REPLACE, DefaultModelManager.policyFor(WorkInfo.State.BLOCKED, unmetered, connected))
        assertEquals(ExistingWorkPolicy.KEEP, DefaultModelManager.policyFor(WorkInfo.State.ENQUEUED, connected, connected))
        // A running worker is never replaced: its cancellation is cooperative, so a replacement
        // could start while it still writes the same .part file.
        assertEquals(ExistingWorkPolicy.KEEP, DefaultModelManager.policyFor(WorkInfo.State.RUNNING, unmetered, connected))
    }

    @Test
    fun replacingWorkKeepsPartFile() = runBlocking<Unit> {
        val dir = ModelStore.dir(context)
        dir.mkdirs()
        val part = File(ModelStore.fileFor(dir, VoicePack.OMNILINGUAL_MODEL).path + ".part").apply { writeBytes(ByteArray(1024)) }
        val m = manager()
        m.ensureDownloaded()
        m.ensureDownloaded(allowMetered = true)
        assertTrue(part.exists())
        assertEquals(1024L, part.length())
    }

    @Test
    fun storageCheckSubtractsBytesAlreadyOnDisk() = runBlocking<Unit> {
        val dir = ModelStore.dir(context)
        dir.mkdirs()
        // freococo is complete but unmarked; Omnilingual is partly downloaded.
        for (f in listOf(VoicePack.FREOCOCO_MODEL, VoicePack.FREOCOCO_VOCAB)) {
            java.io.RandomAccessFile(ModelStore.fileFor(dir, f), "rw").use { it.setLength(f.bytes) }
        }
        java.io.RandomAccessFile(File(ModelStore.fileFor(dir, VoicePack.OMNILINGUAL_MODEL).path + ".part"), "rw")
            .use { it.setLength(1_000_000) }
        manager().ensureDownloaded()
        val missing = VoicePack.OMNILINGUAL_MODEL.bytes - 1_000_000 + VoicePack.OMNILINGUAL_TOKENS.bytes
        assertEquals(listOf(missing), requestedRoom)
    }

    @Test
    fun storageCheckAsksForFullSizeWhenNothingOnDisk() = runBlocking<Unit> {
        manager().ensureDownloaded()
        assertEquals(listOf(VoicePack.downloadBytes), requestedRoom)
    }

    @Test
    fun staleModelsAreDeletedBeforeTheStorageCheck() = runBlocking<Unit> {
        val root = ModelStore.modelsRoot(context)
        val raj = File(root, "RAJ_0_6B/92b89bafb8b48fe47857665ca834390b7303c7d8").apply { mkdirs() }
        File(raj, "Raj-0.6B-Q8_0.gguf").writeBytes(ByteArray(10))
        val oldPack = File(ModelStore.packRoot(context), "v0").apply { mkdirs() }
        val current = ModelStore.dir(context).apply { mkdirs() }
        val part = File(ModelStore.fileFor(current, VoicePack.OMNILINGUAL_MODEL).path + ".part").apply { writeBytes(ByteArray(10)) }
        manager().ensureDownloaded()
        assertFalse(File(root, "RAJ_0_6B").exists())
        assertFalse(oldPack.exists())
        assertTrue(part.exists())
    }

    @Test
    fun insufficientStorageFailsBeforeNetwork() = runBlocking<Unit> {
        roomForDownload = false
        val m = manager()
        m.ensureDownloaded()
        val failed = awaitValue { m.state.value as? ModelState.Failed }
        assertTrue(failed.reason.contains("storage", ignoreCase = true))
        assertTrue(failed.outOfStorage)
        assertTrue(workInfos().isEmpty())
        assertEquals(0, fetchCalls)
    }

    @Test
    fun progressMapsToDownloadingFraction() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        ModelDownloadWorker.fetcher = { file, _, onBytes ->
            if (file == VoicePack.OMNILINGUAL_MODEL) {
                onBytes(file.bytes / 2)
                gate.await()
            }
        }
        val m = manager()
        m.ensureDownloaded()
        val waiting = awaitValue { m.state.value as? ModelState.Downloading }
        assertTrue("no Wi-Fi yet, so the download waits", waiting.waiting)
        assertTrue("Wi-Fi only until the user picks mobile data", waiting.wifiOnly)
        val id = workInfos().single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
        val before = VoicePack.FREOCOCO_MODEL.bytes + VoicePack.FREOCOCO_VOCAB.bytes
        val expected = (before + VoicePack.OMNILINGUAL_MODEL.bytes / 2).toFloat() / VoicePack.downloadBytes
        val downloading = awaitValue(10_000) {
            (m.state.value as? ModelState.Downloading)?.takeIf { it.progress > 0f }
        }
        assertEquals(expected, downloading.progress, 0.01f)
        assertFalse(downloading.waiting)
        gate.complete(Unit)
    }

    @Test
    fun deleteRemovesFilesAndReturnsMissing() = runBlocking<Unit> {
        installVerified()
        val m = manager()
        m.ensureDownloaded()
        awaitValue { m.state.value as? ModelState.Ready }
        m.delete()
        assertEquals(ModelState.Missing, m.state.value)
        assertNull(m.files())
        assertFalse(ModelStore.packRoot(context).exists())
    }

    @Test
    fun deleteAfterFailureReturnsMissing() = runBlocking<Unit> {
        val m = manager()
        failDownload(m)
        m.delete()
        Thread.sleep(300)
        assertEquals(ModelState.Missing, m.state.value)
    }
}
