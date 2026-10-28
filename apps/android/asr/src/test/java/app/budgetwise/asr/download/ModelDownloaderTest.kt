package app.budgetwise.asr.download

import app.budgetwise.asr.ModelFile
import app.budgetwise.asr.RajVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: ModelDownloader
    private val variant = RajVariant.RAJ_0_6B

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = ModelDownloader(OkHttpClient(), ModelSource(server.url("/")))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun fileFor(data: ByteArray) = ModelFile("gguf/fake.gguf", data.size.toLong(), sha256(data))

    private fun body(b: ByteArray, from: Int = 0) = Buffer().write(b, from, b.size - from)

    @Test
    fun urlsUsePinnedRevisionNotMain() {
        val source = ModelSource()
        assertEquals(
            "https://huggingface.co/aungzaythant/Raj-0.6B/resolve/92b89bafb8b48fe47857665ca834390b7303c7d8/gguf/Raj-0.6B-Q8_0.gguf",
            source.urlFor(RajVariant.RAJ_0_6B, RajVariant.RAJ_0_6B.model).toString(),
        )
        for (v in RajVariant.values()) {
            for (f in listOf(v.model, v.mmproj)) {
                val url = source.urlFor(v, f).toString()
                assertFalse(url, url.contains("/main/"))
                assertTrue(url, url.contains("/resolve/${v.revision}/"))
                assertEquals(40, v.revision.length)
                assertEquals(64, f.sha256.length)
            }
        }
        assertEquals("raj-0.6b-q8_0@92b89ba", RajVariant.RAJ_0_6B.modelId)
        assertEquals("raj-1.7b-q8_0@7dbed37", RajVariant.RAJ_1_7B.modelId)
        assertEquals(639_442_816L + 207_941_216L, RajVariant.RAJ_0_6B.downloadBytes)
    }

    @Test
    fun followsRedirectToCdn() = runBlocking {
        val data = payload(5000)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/cdn/blob")))
        server.enqueue(MockResponse().setBody(body(data)))
        val dest = File(tmp.root, "m.gguf")
        downloader.fetch(variant, fileFor(data), dest) {}
        assertArrayEquals(data, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())
        assertEquals("/aungzaythant/Raj-0.6B/resolve/${variant.revision}/gguf/fake.gguf", server.takeRequest().path)
        assertEquals("/cdn/blob", server.takeRequest().path)
    }

    @Test
    fun resumesWithRangeHeader() = runBlocking {
        val data = payload(4000)
        val dest = File(tmp.root, "m.gguf")
        File(dest.path + ".part").writeBytes(data.copyOfRange(0, 1000))
        server.enqueue(MockResponse().setResponseCode(206).setBody(body(data, 1000)))
        val seen = mutableListOf<Long>()
        downloader.fetch(variant, fileFor(data), dest) { seen += it }
        assertEquals("bytes=1000-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(data, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())
        assertEquals(4000L, seen.last())
        assertTrue(seen.all { it >= 1000L })
    }

    @Test
    fun restartsWhenServerIgnoresRange() = runBlocking {
        val data = payload(4000)
        val dest = File(tmp.root, "m.gguf")
        File(dest.path + ".part").writeBytes(ByteArray(1000) { 9 })
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(data)))
        downloader.fetch(variant, fileFor(data), dest) {}
        assertEquals("bytes=1000-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(data, dest.readBytes())
    }

    @Test
    fun hashMismatchDeletesPartAndThrows() {
        val data = payload(3000)
        val bad = fileFor(data).copy(sha256 = "0".repeat(64))
        server.enqueue(MockResponse().setBody(body(data)))
        val dest = File(tmp.root, "m.gguf")
        assertThrows(DownloadException.HashMismatch::class.java) {
            runBlocking { downloader.fetch(variant, bad, dest) {} }
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun resumedDownloadWithCorruptedTailFailsSha() {
        val data = payload(4000)
        val dest = File(tmp.root, "m.gguf")
        File(dest.path + ".part").writeBytes(data.copyOfRange(0, 1000))
        val tail = data.copyOfRange(1000, 4000).also { it[2999 - 5] = (it[2999 - 5] + 1).toByte() }
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(tail)))
        assertThrows(DownloadException.HashMismatch::class.java) {
            runBlocking { downloader.fetch(variant, fileFor(data), dest) {} }
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun httpErrorThrowsHttpException() {
        server.enqueue(MockResponse().setResponseCode(404))
        val e = assertThrows(DownloadException.Http::class.java) {
            runBlocking { downloader.fetch(variant, fileFor(payload(10)), File(tmp.root, "m.gguf")) {} }
        }
        assertEquals(404, e.code)
    }

    @Test
    fun oversizedPartIsDiscarded() = runBlocking {
        val data = payload(2000)
        val dest = File(tmp.root, "m.gguf")
        File(dest.path + ".part").writeBytes(ByteArray(5000))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(data)))
        downloader.fetch(variant, fileFor(data), dest) {}
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(data, dest.readBytes())
    }

    @Test
    fun cancellationKeepsPartFile() {
        val data = payload(1_000_000)
        server.enqueue(MockResponse().setBody(body(data)))
        val dest = File(tmp.root, "m.gguf")
        val part = File(dest.path + ".part")
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val job: Job = coroutineContext[Job]!!
                downloader.fetch(variant, fileFor(data), dest) { job.cancel() }
            }
        }
        assertTrue(part.exists())
        assertTrue(part.length() > 0)
        assertFalse(dest.exists())
    }
}
