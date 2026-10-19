package app.budgetwise.asr.engine

import android.content.Context
import app.budgetwise.asr.AsrException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private const val STATUS_ERROR = 2
private const val CONTEXT_TOKENS = 1024

class NativeRajEngineFactory(
    context: Context,
    private val forcedLanguage: String?,
) : RajEngineFactory {
    private val appContext = context.applicationContext

    override fun open(files: ModelFiles, threads: Int): RajEngine {
        RajNative.initBackendsOnce(appContext)
        val handle = RajNative.load(
            files.model.path,
            files.mmproj.path,
            threads,
            CONTEXT_TOKENS,
            forcedLanguage,
        )
        if (handle == 0L) throw AsrException.EngineFailure(STATUS_ERROR, RajNative.lastError(0L))
        return NativeRajEngine(handle)
    }
}

internal class NativeRajEngine(handle: Long) : RajEngine {
    private val handle = AtomicLong(handle)

    // Calls on the handle hold the read side, so close() frees only after in-flight calls return.
    private val guard = ReentrantReadWriteLock()

    override fun transcribe(pcm: FloatArray, maxNewTokens: Int): EngineOutput = guard.read {
        val h = liveHandle()
        RajNative.transcribe(h, pcm, maxNewTokens)
            ?: throw AsrException.EngineFailure(STATUS_ERROR, RajNative.lastError(h))
    }

    override fun abort() = guard.read { RajNative.abort(liveHandle()) }

    override fun close() {
        val h = handle.get()
        if (h == 0L) return
        // Stop a run in flight first, otherwise the write lock waits for the whole transcribe.
        guard.read { if (handle.get() == h) RajNative.abort(h) }
        guard.write {
            val owned = handle.getAndSet(0L)
            if (owned != 0L) RajNative.free(owned)
        }
    }

    private fun liveHandle(): Long {
        val h = handle.get()
        check(h != 0L) { "engine is closed" }
        return h
    }
}
