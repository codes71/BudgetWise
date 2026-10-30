package app.budgetwise.data.net

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

sealed interface ApiResult<out T> {
    data class Ok<T>(val v: T) : ApiResult<T>
    data class Unauthorized(val msg: String) : ApiResult<Nothing>
    data class Failure(val code: String, val msg: String, val retryable: Boolean) : ApiResult<Nothing>
}

@Serializable
private data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
private data class ErrorBody(val code: String? = null, val message: String? = null)

/** [ApiResult.Failure.code] when the call failed with an IOException (no connection, timeout, reply cut off). */
const val NETWORK_ERROR_CODE = "network"

/** Runs a Retrofit call and maps every failure mode to an [ApiResult]; never throws except on cancellation. */
suspend fun <T> safeApi(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    mapHttp(e.code(), e.response()?.errorBody().readOrNull())
} catch (e: IOException) {
    ApiResult.Failure(NETWORK_ERROR_CODE, e.message ?: "Network error", retryable = true)
} catch (e: SerializationException) {
    ApiResult.Failure("bad_response", "Unexpected server response", retryable = false)
} catch (e: IllegalArgumentException) {
    // kotlinx coerces some decode errors to IllegalArgumentException (e.g. bad enum or number format).
    ApiResult.Failure("bad_response", "Unexpected server response", retryable = false)
}

/** For endpoints declared as `Response<Unit>` (Retrofit does not throw on non-2xx for those). */
suspend fun safeApiResponse(block: suspend () -> Response<Unit>): ApiResult<Unit> =
    when (val r = safeApi(block)) {
        is ApiResult.Ok ->
            if (r.v.isSuccessful) ApiResult.Ok(Unit) else mapHttp(r.v.code(), r.v.errorBody().readOrNull())
        is ApiResult.Unauthorized -> r
        is ApiResult.Failure -> r
    }

/** Reading an error body can fail mid-stream; classify by status instead of letting it escape. */
private fun ResponseBody?.readOrNull(): String? = try {
    this?.string()
} catch (_: IOException) {
    null
}

private fun mapHttp(status: Int, rawBody: String?): ApiResult<Nothing> {
    val parsed = rawBody?.let {
        try {
            WireJson.decodeFromString<ErrorEnvelope>(it).error
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    val message = parsed?.message ?: "HTTP $status"
    if (status == 401) return ApiResult.Unauthorized(message)
    return ApiResult.Failure(
        code = parsed?.code ?: "http_$status",
        msg = message,
        retryable = status >= 500 || status == 429 || status == 408,
    )
}
