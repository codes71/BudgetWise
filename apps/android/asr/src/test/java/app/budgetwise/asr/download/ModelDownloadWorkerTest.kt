package app.budgetwise.asr.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.budgetwise.asr.VoicePack
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
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDownloadWorkerTest {
    private lateinit var context: Context
    private val saved = ModelDownloadWorker.fetcher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        ModelDownloadWorker.fetcher = saved
        ModelStore.modelsRoot(context).deleteRecursively()
    }

    private fun run(attempt: Int = 0, pack: String = VoicePack.VERSION): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_PACK to pack))
            .setRunAttemptCount(attempt)
            .build()
            .doWork()
    }

    private fun writeSized(dest: File, bytes: Long) {
        dest.parentFile?.mkdirs()
        java.io.RandomAccessFile(dest, "rw").use { it.setLength(bytes) }
    }

    @Test
    fun downloadsEveryFileThenWritesMarkerLast() {
        val order = mutableListOf<String>()
        ModelDownloadWorker.fetcher = { f, dest, _ ->
            assertNull("marker before the last file", ModelStore.verified(context))
            writeSized(dest, f.bytes)
            order += dest.name
        }
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(listOf("freococo.onnx", "vocab.json", "model.int8.onnx", "tokens.txt"), order)
        assertNotNull(ModelStore.verified(context))
    }

    @Test
    fun completedFilesAreNotFetchedAgain() {
        val dir = ModelStore.dir(context)
        writeSized(ModelStore.fileFor(dir, VoicePack.FREOCOCO_MODEL), VoicePack.FREOCOCO_MODEL.bytes)
        val fetched = mutableListOf<String>()
        ModelDownloadWorker.fetcher = { f, dest, _ ->
            writeSized(dest, f.bytes)
            fetched += f.name
        }
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(listOf("vocab.json", "model.int8.onnx", "tokens.txt"), fetched)
    }

    @Test
    fun unknownPackFailsWithoutFetching() {
        var fetched = 0
        ModelDownloadWorker.fetcher = { _, _, _ -> fetched++ }
        val result = run(pack = "RAJ_0_6B")
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(0, fetched)
    }

    @Test
    fun unconfiguredHostFailsAtOnce() {
        ModelDownloadWorker.fetcher = { f, _, _ -> throw DownloadException.NotConfigured(f.path) }
        val result = run()
        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(result.outputData().getString(ModelDownloadWorker.KEY_REASON)!!.contains("not configured"))
    }

    @Test
    fun hashMismatchFailsWithoutMarker() {
        ModelDownloadWorker.fetcher = { f, _, _ -> throw DownloadException.HashMismatch(f.path) }
        val result = run()
        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(result.outputData().getString(ModelDownloadWorker.KEY_REASON)!!.contains("hash"))
        assertNull(ModelStore.verified(context))
    }

    @Test
    fun http5xxAndIoRetryButHttp4xxFails() {
        ModelDownloadWorker.fetcher = { _, _, _ -> throw DownloadException.Http(503) }
        assertEquals(ListenableWorker.Result.retry(), run())
        ModelDownloadWorker.fetcher = { _, _, _ -> throw IOException("reset") }
        assertEquals(ListenableWorker.Result.retry(), run())
        ModelDownloadWorker.fetcher = { _, _, _ -> throw DownloadException.Http(404) }
        assertTrue(run() is ListenableWorker.Result.Failure)
    }

    private fun ListenableWorker.Result.outputData() = (this as ListenableWorker.Result.Failure).outputData
}
