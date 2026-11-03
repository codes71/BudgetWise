package app.budgetwise.updates

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Dedicated unauthenticated client. Redirects are checked before any request is sent to their destination. */
internal class UpdateHttp(private val client: OkHttpClient) {
    @Volatile private var active: Call? = null

    fun cancel() = active?.cancel()

    suspend fun get(url: HttpUrl, apk: Boolean): Response {
        var destination = url
        repeat(6) { hop ->
            val call = client.newCall(Request.Builder().url(destination).header("Accept-Encoding", "identity").build())
            active = call
            val response = await(call)
            if (response.code !in listOf(301, 302, 303, 307, 308)) return response
            response.use {
                if (!apk || hop == 5) throw InvalidUpdate("Unexpected redirect")
                val location = it.header("Location") ?: throw InvalidUpdate("Missing redirect destination")
                destination = UpdatePolicy.redirectUrl(destination.resolve(location)?.toString() ?: "")
            }
        }
        throw InvalidUpdate("Too many redirects")
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, returned, _ -> returned.close() }
            }
        })
    }

    fun descriptor(response: Response): ByteArray {
        val body = response.body ?: throw InvalidUpdate("Missing descriptor")
        if (body.contentLength() > UpdatePolicy.MAX_DESCRIPTOR_BYTES) throw InvalidUpdate("Descriptor too large")
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > UpdatePolicy.MAX_DESCRIPTOR_BYTES) throw InvalidUpdate("Descriptor too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
}
