package app.budgetwise.asr.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.budgetwise.asr.RajVariant
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
    private val v = RajVariant.RAJ_0_6B
    private val saved = ModelDownloadWorker.fetcher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        ModelDownloadWorker.fetcher = saved
        ModelStore.variantRoot(context, v).deleteRecursively()
    }

    private fun run(attempt: Int = 0): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_VARIANT to v.name))
            .setRunAttemptCount(attempt)
            .build()
            .doWork()
    }

    private fun writeSized(dest: File, bytes: Long) {
        dest.parentFile?.mkdirs()
        java.io.RandomAccessFile(dest, "rw").use { it.setLength(bytes) }
    }

    @Test
    fun downloadsBothFilesThenWritesMarkerLast() {
        val order = mutableListOf<String>()
        ModelDownloadWorker.fetcher = { _, f, dest, _ ->
            writeSized(dest, f.bytes)
            order += dest.name
        }
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(listOf("Raj-0.6B-Q8_0.gguf", "mmproj-Raj-0.6B-Q8_0.gguf"), order)
        assertNotNull(ModelStore.verified(context, v))
    }

    @Test
    fun hashMismatchFailsWithoutMarker() {
        ModelDownloadWorker.fetcher = { _, f, _, _ -> throw DownloadException.HashMismatch(f.path) }
        val result = run()
        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(result.outputData().getString(ModelDownloadWorker.KEY_REASON)!!.contains("hash"))
        assertNull(ModelStore.verified(context, v))
    }

    @Test
    fun http5xxAndIoRetryButHttp4xxFails() {
        ModelDownloadWorker.fetcher = { _, _, _, _ -> throw DownloadException.Http(503) }
        assertEquals(ListenableWorker.Result.retry(), run())
        ModelDownloadWorker.fetcher = { _, _, _, _ -> throw IOException("reset") }
        assertEquals(ListenableWorker.Result.retry(), run())
        ModelDownloadWorker.fetcher = { _, _, _, _ -> throw DownloadException.Http(404) }
        assertTrue(run() is ListenableWorker.Result.Failure)
    }

    private fun ListenableWorker.Result.outputData() = (this as ListenableWorker.Result.Failure).outputData
}
