package app.budgetwise.asr.download

import app.budgetwise.asr.ModelFile
import app.budgetwise.asr.ModelOrigin
import app.budgetwise.asr.VoicePack
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
    private val origin = ModelOrigin.HuggingFace("org/repo", "0123456789abcdef0123456789abcdef01234567")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = ModelDownloader(OkHttpClient(), ModelSource(server.url("/"), server.url("/freococo/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun fileFor(data: ByteArray) = ModelFile(origin, "onnx/fake.onnx", data.size.toLong(), sha256(data))

    private fun body(b: ByteArray, from: Int = 0) = Buffer().write(b, from, b.size - from)

    @Test
    fun omnilingualUrlsUsePinnedRevisionNotMain() {
        val source = ModelSource()
        assertEquals(
            "https://huggingface.co/csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-1B-ctc-int8-2025-11-12" +
                "/resolve/17db76eab583b0b868ffb0df104ab879145087e5/model.int8.onnx",
            source.urlFor(VoicePack.OMNILINGUAL_MODEL).toString(),
        )
        for (f in VoicePack.files.filter { it.origin is ModelOrigin.HuggingFace }) {
            val revision = (f.origin as ModelOrigin.HuggingFace).revision
            val url = source.urlFor(f).toString()
            assertFalse(url, url.contains("/main/"))
            assertTrue(url, url.contains("/resolve/$revision/"))
            assertEquals(40, revision.length)
        }
    }

    @Test
    fun freococoUrlsComeFromTheConfiguredBase() {
        val source = ModelSource(freococoBase = "https://models.example.com/voice/v1/")
        assertEquals("https://models.example.com/voice/v1/freococo.onnx", source.urlFor(VoicePack.FREOCOCO_MODEL).toString())
        assertEquals("https://models.example.com/voice/v1/vocab.json", source.urlFor(VoicePack.FREOCOCO_VOCAB).toString())
    }

    @Test
    fun theDefaultFreococoHostIsItsHuggingFaceRepoAtAPinnedCommit() {
        assertEquals(
            "https://huggingface.co/thura2/freococo-myanmar-asr-onnx/resolve/e5041e673745d9fcd62872710820c22d8ccb9307/freococo.onnx",
            ModelSource().urlFor(VoicePack.FREOCOCO_MODEL).toString(),
        )
    }

    @Test
    fun placeholderFreococoBaseIsNotConfigured() {
        for (base in listOf("https://freococo-models.budgetwise.invalid/", "not a url")) {
            val e = assertThrows(DownloadException.NotConfigured::class.java) {
                ModelSource(freococoBase = base).urlFor(VoicePack.FREOCOCO_MODEL)
            }
            assertEquals("freococo.onnx", e.file)
        }
    }

    @Test
    fun fetchesFreococoFileFromItsHost() = runBlocking {
        val data = payload(100)
        server.enqueue(MockResponse().setBody(body(data)))
        val dest = File(tmp.root, "freococo.onnx")
        downloader.fetch(ModelFile(ModelOrigin.FreococoHost, "freococo.onnx", 100, sha256(data)), dest) {}
        assertEquals("/freococo/freococo.onnx", server.takeRequest().path)
        assertArrayEquals(data, dest.readBytes())
    }

    @Test
    fun followsRedirectToCdn() = runBlocking {
        val data = payload(5000)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/cdn/blob")))
        server.enqueue(MockResponse().setBody(body(data)))
        val dest = File(tmp.root, "m.onnx")
        downloader.fetch(fileFor(data), dest) {}
        assertArrayEquals(data, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())
        assertEquals("/org/repo/resolve/${origin.revision}/onnx/fake.onnx", server.takeRequest().path)
        assertEquals("/cdn/blob", server.takeRequest().path)
    }

    @Test
    fun resumesWithRangeHeader() = runBlocking {
        val data = payload(4000)
        val dest = File(tmp.root, "m.onnx")
        File(dest.path + ".part").writeBytes(data.copyOfRange(0, 1000))
        server.enqueue(MockResponse().setResponseCode(206).setBody(body(data, 1000)))
        val seen = mutableListOf<Long>()
        downloader.fetch(fileFor(data), dest) { seen += it }
        assertEquals("bytes=1000-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(data, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())
        assertEquals(4000L, seen.last())
        assertTrue(seen.all { it >= 1000L })
    }

    @Test
    fun restartsWhenServerIgnoresRange() = runBlocking {
        val data = payload(4000)
        val dest = File(tmp.root, "m.onnx")
        File(dest.path + ".part").writeBytes(ByteArray(1000) { 9 })
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(data)))
        downloader.fetch(fileFor(data), dest) {}
        assertEquals("bytes=1000-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(data, dest.readBytes())
    }

    @Test
    fun hashMismatchDeletesPartAndThrows() {
        val data = payload(3000)
        val bad = fileFor(data).copy(sha256 = "0".repeat(64))
        server.enqueue(MockResponse().setBody(body(data)))
        val dest = File(tmp.root, "m.onnx")
        assertThrows(DownloadException.HashMismatch::class.java) {
            runBlocking { downloader.fetch(bad, dest) {} }
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun resumedDownloadWithCorruptedTailFailsSha() {
        val data = payload(4000)
        val dest = File(tmp.root, "m.onnx")
        File(dest.path + ".part").writeBytes(data.copyOfRange(0, 1000))
        val tail = data.copyOfRange(1000, 4000).also { it[2999 - 5] = (it[2999 - 5] + 1).toByte() }
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(tail)))
        assertThrows(DownloadException.HashMismatch::class.java) {
            runBlocking { downloader.fetch(fileFor(data), dest) {} }
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun httpErrorThrowsHttpException() {
        server.enqueue(MockResponse().setResponseCode(404))
        val e = assertThrows(DownloadException.Http::class.java) {
            runBlocking { downloader.fetch(fileFor(payload(10)), File(tmp.root, "m.onnx")) {} }
        }
        assertEquals(404, e.code)
    }

    @Test
    fun oversizedPartIsDiscarded() = runBlocking {
        val data = payload(2000)
        val dest = File(tmp.root, "m.onnx")
        File(dest.path + ".part").writeBytes(ByteArray(5000))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(data)))
        downloader.fetch(fileFor(data), dest) {}
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(data, dest.readBytes())
    }

    @Test
    fun cancellationKeepsPartFile() {
        val data = payload(1_000_000)
        server.enqueue(MockResponse().setBody(body(data)))
        val dest = File(tmp.root, "m.onnx")
        val part = File(dest.path + ".part")
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val job: Job = coroutineContext[Job]!!
                downloader.fetch(fileFor(data), dest) { job.cancel() }
            }
        }
        assertTrue(part.exists())
        assertTrue(part.length() > 0)
        assertFalse(dest.exists())
    }
}
