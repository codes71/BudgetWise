package app.budgetwise.asr.engine

import android.content.Context
import app.budgetwise.asr.AsrException
import java.util.concurrent.TimeUnit
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

    /** Loads are serialized so the process-wide load error read after a failure is this load's. */
    override fun open(files: ModelFiles, threads: Int): RajEngine = synchronized(loadLock) {
        RajNative.initBackendsOnce(appContext)
        val handle = RajNative.load(
            files.model.path,
            files.mmproj.path,
            threads,
            CONTEXT_TOKENS,
            forcedLanguage,
        )
        if (handle == 0L) throw AsrException.EngineFailure(STATUS_ERROR, RajNative.lastError(0L))
        NativeRajEngine(handle)
    }

    private companion object {
        val loadLock = Any()
    }
}

internal class NativeRajEngine(handle: Long) : RajEngine {
    private val handle = AtomicLong(handle)

    @Volatile
    private var closing = false

    // Calls on the handle hold the read side, so close() frees only after in-flight calls return.
    private val guard = ReentrantReadWriteLock()

    override fun transcribe(pcm: FloatArray, maxNewTokens: Int): EngineOutput = guard.read {
        val h = liveHandle()
        RajNative.transcribe(h, pcm, maxNewTokens)
            ?: throw AsrException.EngineFailure(STATUS_ERROR, RajNative.lastError(h))
    }

    override fun abort() {
        val read = guard.readLock()
        // A close in progress is already aborting the run, so there is nothing left to do.
        if (!read.tryLock()) {
            liveHandle()
            return
        }
        try {
            // A close waiting for the write lock re-issues the abort itself; after close, liveHandle throws.
            if (closing && handle.get() != 0L) return
            RajNative.abort(liveHandle())
        } finally {
            read.unlock()
        }
    }

    /**
     * Aborts a run in flight and frees the native engine. May block for up to one audio encode,
     * which the engine cannot interrupt. A second call is a no-op.
     * Synchronized so a single closer frees the handle while others only wait.
     */
    @Synchronized
    override fun close() {
        if (handle.get() == 0L) return
        closing = true
        val write = guard.writeLock()
        while (!write.tryLock(ABORT_RETRY_MS, TimeUnit.MILLISECONDS)) {
            // An abort that lands during the encoder is picked up at the next decode, so keep
            // re-issuing it until the in-flight call releases its read lock.
            val h = handle.get()
            if (h != 0L) RajNative.abort(h)
        }
        try {
            val owned = handle.getAndSet(0L)
            if (owned != 0L) RajNative.free(owned)
        } finally {
            write.unlock()
        }
    }

    private fun liveHandle(): Long {
        val h = handle.get()
        check(h != 0L && !closing) { "engine is closed" }
        return h
    }

    private companion object {
        const val ABORT_RETRY_MS = 50L
    }
}
