package app.budgetwise.asr.download

import app.budgetwise.asr.ModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed class DownloadException(msg: String) : Exception(msg) {
    class HashMismatch(val file: String) : DownloadException(file)
    class Http(val code: Int) : DownloadException("http $code")

    /** The build has no download host for [file] (see `budgetwise.freococoModelBaseUrl`). */
    class NotConfigured(val file: String) : DownloadException("no download host configured for $file")
}

class ModelDownloader(private val client: OkHttpClient, private val source: ModelSource) {

    /**
     * Downloads [f] to `dest.path + ".part"`, resuming from any existing partial, and renames to [dest]
     * only after size and sha256 both match. [onBytes] receives the total bytes on disk so far.
     */
    suspend fun fetch(f: ModelFile, dest: File, onBytes: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val part = File(dest.path + ".part")
            dest.parentFile?.mkdirs()
            if (part.exists() && part.length() > f.bytes) part.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            var have = if (part.exists()) hashExisting(part, digest) else 0L

            if (have < f.bytes) {
                val request = Request.Builder().url(source.urlFor(f)).apply {
                    if (have > 0) header("Range", "bytes=$have-")
                }.build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw DownloadException.Http(response.code)
                    val append = response.code == 206 && have > 0
                    if (!append) {
                        digest.reset()
                        have = 0
                    }
                    val body = response.body ?: throw DownloadException.Http(response.code)
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(CHUNK)
                        body.byteStream().use { input ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                have += n
                                onBytes(have)
                                if (have > f.bytes) break
                            }
                        }
                    }
                }
            } else {
                onBytes(have)
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (have != f.bytes || !actual.equals(f.sha256, ignoreCase = true)) {
                part.delete()
                throw DownloadException.HashMismatch(f.path)
            }
            dest.delete()
            if (!part.renameTo(dest)) {
                part.delete()
                throw java.io.IOException("could not move ${part.path} to ${dest.path}")
            }
        }

    private suspend fun hashExisting(part: File, digest: MessageDigest): Long {
        var total = 0L
        part.inputStream().use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                total += n
            }
        }
        return total
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
