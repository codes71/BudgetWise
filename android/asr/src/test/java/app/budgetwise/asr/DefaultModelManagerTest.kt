package app.budgetwise.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.budgetwise.asr.download.ModelDownloadWorker
import app.budgetwise.asr.download.ModelStore
import app.budgetwise.asr.download.StorageCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
        ModelDownloadWorker.fetcher = { _, _, _, _ -> fetchCalls++ }
    }

    @After
    fun tearDown() {
        ModelDownloadWorker.fetcher = savedFetcher
        context.noBackupFilesDir.resolve("models").deleteRecursively()
        context.getSharedPreferences("raj_model_manager", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun manager() = DefaultModelManager(context, workManager, storage)

    /** Writes both files with the right sizes (sparse) and a marker, as a finished download would. */
    private fun installVerified(v: RajVariant): File {
        val dir = ModelStore.dir(context, v)
        dir.mkdirs()
        for (f in listOf(v.model, v.mmproj)) {
            java.io.RandomAccessFile(ModelStore.fileFor(dir, f), "rw").use { it.setLength(f.bytes) }
        }
        ModelStore.writeMarker(dir, v)
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

    private fun workInfos(v: RajVariant) =
        workManager.getWorkInfosForUniqueWork(ModelDownloadWorker.uniqueName(v)).get()

    @Test
    fun missingWhenNoFiles() {
        val m = manager()
        assertEquals(ModelState.Missing, m.state.value)
        assertNull(m.activeVariant.value)
        assertNull(m.filesFor(RajVariant.RAJ_0_6B))
    }

    @Test
    fun readyAfterVerifiedMarkerPresent() = runBlocking<Unit> {
        val v = RajVariant.RAJ_0_6B
        val dir = installVerified(v)
        val m = manager()
        m.ensureDownloaded(v)
        val ready = awaitValue { m.state.value as? ModelState.Ready }
        assertEquals(dir.path, ready.path)
        assertNotNull(m.filesFor(v))
        assertTrue(workInfos(v).isEmpty())
        // Restart: active variant persists and state is Ready immediately.
        val again = manager()
        assertEquals(v, again.activeVariant.value)
        assertEquals(ModelState.Ready(dir.path), again.state.value)
    }

    @Test
    fun missingFileIsNotVerified() {
        val v = RajVariant.RAJ_0_6B
        val dir = installVerified(v)
        ModelStore.fileFor(dir, v.mmproj).delete()
        assertNull(manager().filesFor(v))
    }

    @Test
    fun truncatedFileIsNotVerified() {
        val v = RajVariant.RAJ_0_6B
        val dir = installVerified(v)
        java.io.RandomAccessFile(ModelStore.fileFor(dir, v.model), "rw").use { it.setLength(v.model.bytes - 1) }
        assertNull(manager().filesFor(v))
    }

    @Test
    fun markerWithMismatchedShaIsNotVerified() {
        val v = RajVariant.RAJ_0_6B
        val dir = installVerified(v)
        val marker = File(dir, ModelStore.MARKER)
        marker.writeText(marker.readText().replace(v.model.sha256, "0".repeat(64)))
        assertNull(manager().filesFor(v))
    }

    @Test
    fun corruptMarkerIsNotVerifiedAndDoesNotCrash() {
        val v = RajVariant.RAJ_0_6B
        val dir = installVerified(v)
        File(dir, ModelStore.MARKER).writeText("{not json")
        val m = manager()
        assertNull(m.filesFor(v))
        File(dir, ModelStore.MARKER).writeText("")
        assertNull(m.filesFor(v))
    }

    /** Runs a download for [v] that fails with a hash mismatch and waits for the manager to show Failed. */
    private suspend fun failDownload(m: DefaultModelManager, v: RajVariant) {
        ModelDownloadWorker.fetcher = { _, f, _, _ -> throw app.budgetwise.asr.download.DownloadException.HashMismatch(f.path) }
        m.ensureDownloaded(v)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(workInfos(v).single().id)
        awaitValue { m.state.value as? ModelState.Failed }
    }

    @Test
    fun deletingOtherVariantKeepsFailedState() = runBlocking<Unit> {
        val a = RajVariant.RAJ_0_6B
        val b = RajVariant.RAJ_1_7B
        installVerified(b)
        val m = manager()
        failDownload(m, a)
        m.delete(b)
        Thread.sleep(300)
        assertTrue(m.state.value is ModelState.Failed)
    }

    @Test
    fun restartRestoresFailedStateFromWork() = runBlocking<Unit> {
        val a = RajVariant.RAJ_0_6B
        failDownload(manager(), a)
        val again = manager()
        val s = awaitValue { again.state.value as? ModelState.Failed }
    }

    @Test
    fun restartRestoresDownloadingStateFromWork() = runBlocking<Unit> {
        val a = RajVariant.RAJ_0_6B
        manager().ensureDownloaded(a)
        val again = manager()
        val s = awaitValue { again.state.value as? ModelState.Downloading }
    }

    @Test
    fun enqueuesUniqueWorkUnmeteredByDefault() = runBlocking<Unit> {
        val v = RajVariant.RAJ_0_6B
        val m = manager()
        m.ensureDownloaded(v)
        m.ensureDownloaded(v)
        val infos = workInfos(v)
        assertEquals(1, infos.size)
        assertEquals(NetworkType.UNMETERED, infos[0].constraints.requiredNetworkType)
        assertEquals(v, m.activeVariant.value)
        val downloading = awaitValue { m.state.value as? ModelState.Downloading }
    }

    @Test
    fun allowMeteredUsesConnected() = runBlocking<Unit> {
        val v = RajVariant.RAJ_1_7B
        manager().ensureDownloaded(v, allowMetered = true)
        assertEquals(NetworkType.CONNECTED, workInfos(v).single().constraints.requiredNetworkType)
    }

    @Test
    fun allowMeteredUpgradesPendingUnmeteredWork() = runBlocking<Unit> {
        val v = RajVariant.RAJ_0_6B
        val m = manager()
        m.ensureDownloaded(v)
        assertEquals(NetworkType.UNMETERED, workInfos(v).single().constraints.requiredNetworkType)
        m.ensureDownloaded(v, allowMetered = true)
        val live = workInfos(v).filter { !it.state.isFinished }
        assertEquals(1, live.size)
        assertEquals(NetworkType.CONNECTED, live.single().constraints.requiredNetworkType)
    }

    @Test
    fun replacingWorkKeepsPartFile() = runBlocking<Unit> {
        val v = RajVariant.RAJ_0_6B
        val dir = ModelStore.dir(context, v)
        dir.mkdirs()
        val part = File(ModelStore.fileFor(dir, v.model).path + ".part").apply { writeBytes(ByteArray(1024)) }
        val m = manager()
        m.ensureDownloaded(v)
        m.ensureDownloaded(v, allowMetered = true)
        assertTrue(part.exists())
        assertEquals(1024L, part.length())
    }

    @Test
    fun storageCheckSubtractsBytesAlreadyOnDisk() = runBlocking<Unit> {
        val v = RajVariant.RAJ_0_6B
        val dir = ModelStore.dir(context, v)
        dir.mkdirs()
        // Model file is complete but unmarked; mmproj is half downloaded.
        java.io.RandomAccessFile(ModelStore.fileFor(dir, v.model), "rw").use { it.setLength(v.model.bytes) }
        java.io.RandomAccessFile(File(ModelStore.fileFor(dir, v.mmproj).path + ".part"), "rw")
            .use { it.setLength(1_000_000) }
        manager().ensureDownloaded(v)
        assertEquals(listOf(v.mmproj.bytes - 1_000_000), requestedRoom)
    }

    @Test
    fun storageCheckAsksForFullSizeWhenNothingOnDisk() = runBlocking<Unit> {
        val v = RajVariant.RAJ_1_7B
        manager().ensureDownloaded(v)
        assertEquals(listOf(v.downloadBytes), requestedRoom)
    }

    @Test
    fun insufficientStorageFailsBeforeNetwork() = runBlocking<Unit> {
        roomForDownload = false
        val v = RajVariant.RAJ_1_7B
        val m = manager()
        m.ensureDownloaded(v)
        val failed = awaitValue { m.state.value as? ModelState.Failed }
        assertTrue(failed.reason.contains("storage", ignoreCase = true))
        assertTrue(workInfos(v).isEmpty())
        assertEquals(0, fetchCalls)
    }

    @Test
    fun progressMapsToDownloadingFraction() = runBlocking<Unit> {
        val v = RajVariant.RAJ_0_6B
        val gate = CompletableDeferred<Unit>()
        ModelDownloadWorker.fetcher = { variant, file, _, onBytes ->
            if (file == variant.model) {
                onBytes(file.bytes / 2)
                gate.await()
            }
        }
        val m = manager()
        m.ensureDownloaded(v)
        val id = workInfos(v).single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
        val expected = (v.model.bytes / 2).toFloat() / v.downloadBytes
        val downloading = awaitValue(10_000) {
            (m.state.value as? ModelState.Downloading)?.takeIf { it.progress > 0f }
        }
        assertEquals(expected, downloading.progress, 0.01f)
        gate.complete(Unit)
    }

    @Test
    fun deleteRemovesDirAndReturnsMissing() = runBlocking<Unit> {
        val v = RajVariant.RAJ_0_6B
        installVerified(v)
        val m = manager()
        m.ensureDownloaded(v)
        awaitValue { m.state.value as? ModelState.Ready }
        m.delete(v)
        assertEquals(ModelState.Missing, m.state.value)
        assertNull(m.filesFor(v))
        assertTrue(!ModelStore.variantRoot(context, v).exists())
    }

    @Test
    fun switchingVariantKeepsOtherFiles() = runBlocking<Unit> {
        val small = RajVariant.RAJ_0_6B
        val big = RajVariant.RAJ_1_7B
        installVerified(small)
        installVerified(big)
        val m = manager()
        m.ensureDownloaded(small)
        m.ensureDownloaded(big)
        assertEquals(big, m.activeVariant.value)
        m.delete(big)
        assertNull(m.filesFor(big))
        assertNotNull(m.filesFor(small))
    }
}
