package app.budgetwise.asr.engine

import android.content.Context

internal object RajNative {
    init {
        System.loadLibrary("raj_jni")
    }

    private var backends: String? = null

    /**
     * Loads every ggml backend library found in [nativeLibDir] and returns a
     * description of the registered backends, one per line.
     */
    private external fun initBackends(nativeLibDir: String): String

    /** [initBackends] at most once per process, returning the first call's description. */
    @Synchronized
    fun initBackendsOnce(context: Context): String =
        backends ?: initBackends(context.applicationInfo.nativeLibraryDir).also { backends = it }

    /**
     * Returns 0 on failure, with the reason in `lastError(0)`. That error is process-wide, so it is
     * only meaningful right after a failed load, with loads serialized by the caller. A null [forced]
     * means auto mode.
     */
    external fun load(model: String, mmproj: String, threads: Int, nCtx: Int, forced: String?): Long

    /**
     * Null means failure (see [lastError]). A stopped run throws [java.util.concurrent.CancellationException]
     * from native code.
     */
    external fun transcribe(handle: Long, pcm: FloatArray, maxNewTokens: Int): EngineOutput?

    external fun abort(handle: Long)

    /** At most once per handle, with no other call on it in flight. */
    external fun free(handle: Long)

    /** Handle 0 returns the error of the last failed [load]. */
    external fun lastError(handle: Long): String
}
