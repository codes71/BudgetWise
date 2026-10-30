package app.budgetwise.asr.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.system.Os
import app.budgetwise.asr.AsrException
import app.budgetwise.asr.VoiceModel
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

/** Opens the ONNX Runtime engine for each [VoiceModel]. Loads are serialized; each one reads the whole model. */
class OnnxEngineFactory : SpeechEngineFactory {
    override fun open(model: VoiceModel, files: VoiceFiles): SpeechEngine = synchronized(loadLock) {
        when (model) {
            VoiceModel.FREOCOCO -> FreococoEngine(
                OnnxSessions.open(files.freococoModel),
                FreococoDecoder(FreococoDecoder.parseVocab(files.freococoVocab.readText(Charsets.UTF_8))),
            )
            VoiceModel.OMNILINGUAL -> OmnilingualEngine(
                OnnxSessions.open(files.omnilingualModel),
                OmnilingualDecoder(OmnilingualDecoder.parseTokens(files.omnilingualTokens.readText(Charsets.UTF_8))),
            )
        }
    }

    private companion object {
        val loadLock = Any()
    }
}

/**
 * The session settings shared by both models and by the Mac goldens (`asr/host/onnx/ort_reference.py`, which must
 * run the same onnxruntime version as `libs.versions.toml`).
 *
 * The int8 Omnilingual model is numerically fragile: it quantizes activations on the fly, so a rounding
 * difference anywhere flips int8 values and changes the transcript. On the user's 18 clips (Mac, 1.31.1) a change
 * of optimization level, thread count or kernel family changed as many as 12 of the 18 Omnilingual transcripts,
 * and some settings turned the quiet clip r16 into garbage. freococo gave the PyTorch transcript at every setting.
 * So the settings are chosen for reproducibility, then quality, and not speed:
 * - KleidiAI is off (`mlas.disable_kleidiai`). The Mac M5 runs KleidiAI's SME kernels, a Snapdragon 8 Gen 3 has
 *   no SME; with KleidiAI off both run the same MLAS NEON kernels, so the Mac can reproduce the phone.
 * - [THREADS] is fixed instead of following the core count: 4 threads gave other transcripts than 1 or 2, while
 *   1 and 2 agreed on all 48 clips (18 user, 30 FLEURS). A fixed count gives every phone the same arithmetic.
 * - Graph optimization is off ([OPT_LEVEL]): with it on, even 1 and 2 threads disagreed on some clips, and it
 *   gained nothing on FLEURS (CER 7.9 % off, 8.1 to 8.2 % on). The table is in `asr/host/README.md`.
 * - Weight prepacking is off (`session.disable_prepacking`): while loading it holds a packed copy of the int8
 *   weights next to the originals. Off, Omnilingual's load peak on the Mac fell from 1.58 to 1.26 GB for about
 *   7 % more decode time.
 * - The memory arena and memory patterns are off: inputs change length on every call, and without the arena the
 *   activations of a long utterance go back to the system after the call.
 * None of the last three changed any of the 18 transcripts of either model.
 */
internal object OnnxSessions {
    init {
        // Runs before any ONNX Runtime class can load the native library, so 1DS telemetry never starts
        // in this process (the manifest already removes the provider that would start it at app launch).
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
    }

    const val THREADS = 2
    val OPT_LEVEL = OrtSession.SessionOptions.OptLevel.NO_OPT

    val env: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "budgetwise-asr").also {
            it.setTelemetry(false)
        }
    }

    fun open(model: File): OrtSession = try {
        val environment = env
        OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OPT_LEVEL)
            options.setIntraOpNumThreads(THREADS)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.addConfigEntry("mlas.disable_kleidiai", "1")
            options.addConfigEntry("session.disable_prepacking", "1")
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
            environment.createSession(model.path, options)
        }
    } catch (e: OrtException) {
        throw AsrException.EngineFailure(e.code.ordinal, "could not load ${model.name}: ${e.message}")
    }
}

/**
 * Runs one session call at a time. [abort] terminates the call in flight through its RunOptions; [close] aborts,
 * waits for the call to return and then frees the session.
 */
internal abstract class OnnxEngine(private val session: OrtSession) : SpeechEngine {
    @Volatile
    private var closing = false

    // Calls hold the read side, so close() frees the session only after in-flight calls return.
    private val guard = ReentrantReadWriteLock()

    // Guards the RunOptions of the call in flight, so abort never touches one that was already freed.
    private val runLock = Any()
    private var current: OrtSession.RunOptions? = null
    private var aborted = false

    final override fun transcribe(pcm: FloatArray): String = guard.read {
        check(!closing) { "engine is closed" }
        decode(pcm)
    }

    protected abstract fun decode(pcm: FloatArray): String

    /** Runs the session on [input] as `[1, N]` and hands the single output tensor to [read]. */
    protected fun <T> run(inputName: String, input: FloatArray, read: (OnnxTensor) -> T): T {
        val options = try {
            OrtSession.RunOptions()
        } catch (e: OrtException) {
            throw AsrException.EngineFailure(e.code.ordinal, e.message ?: "onnxruntime error")
        }
        synchronized(runLock) {
            current = options
            aborted = false
        }
        try {
            OnnxTensor.createTensor(OnnxSessions.env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong()))
                .use { tensor ->
                    session.run(mapOf(inputName to tensor), options).use { result ->
                        return read(result.get(0) as OnnxTensor)
                    }
                }
        } catch (e: OrtException) {
            if (synchronized(runLock) { aborted }) throw CancellationException("aborted")
            throw AsrException.EngineFailure(e.code.ordinal, e.message ?: "onnxruntime error")
        } finally {
            synchronized(runLock) { current = null }
            options.close()
        }
    }

    override fun abort() {
        check(!closing) { "engine is closed" }
        terminate()
    }

    private fun terminate() = synchronized(runLock) {
        val options = current ?: return@synchronized
        aborted = true
        try {
            options.setTerminate(true)
        } catch (_: OrtException) {
            // Only fails on a freed handle, and runLock rules that out; close() keeps retrying anyway.
        }
    }

    /** Aborts a call in flight and frees the session. A second call is a no-op. */
    @Synchronized
    override fun close() {
        if (closing) return
        closing = true
        val write = guard.writeLock()
        // ONNX Runtime checks the terminate flag between nodes, so keep setting it until the call returns.
        while (!write.tryLock(ABORT_RETRY_MS, TimeUnit.MILLISECONDS)) terminate()
        try {
            session.close()
        } finally {
            write.unlock()
        }
    }

    private companion object {
        const val ABORT_RETRY_MS = 50L
    }
}

/** freococo.onnx: `audio` float32 [1, N] in, `ids` int64 [1, T] out; the log-mel front end is inside the graph. */
internal class FreococoEngine(session: OrtSession, private val decoder: FreococoDecoder) : OnnxEngine(session) {
    override fun decode(pcm: FloatArray): String = decoder.transcribe(pcm) { audio ->
        run("audio", audio) { out ->
            val ids = out.longBuffer
            LongArray(ids.remaining()) { ids.get(it) }
        }
    }
}

/** model.int8.onnx: `x` float32 [1, N] of normalized audio in, `logits` float32 [1, T, vocab] out. */
internal class OmnilingualEngine(session: OrtSession, private val decoder: OmnilingualDecoder) : OnnxEngine(session) {
    override fun decode(pcm: FloatArray): String = decoder.transcribe(pcm) { normalized ->
        run("x", normalized) { out ->
            val shape = out.info.shape
            check(shape.size == 3 && shape[0] == 1L) { "unexpected logits shape ${shape.contentToString()}" }
            Logits(out.floatBuffer, shape[1].toInt(), shape[2].toInt())
        }
    }
}
