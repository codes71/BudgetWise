package app.budgetwise.asr.demo

import android.app.Application
import androidx.work.WorkManager
import app.budgetwise.asr.DefaultModelManager
import app.budgetwise.asr.MemoryGuard
import app.budgetwise.asr.ModelManager
import app.budgetwise.asr.OnnxSpeechToText

/**
 * Owns the model manager and the speech engine so they survive activity recreation
 * (rotation is locked, but locale or dark-mode changes still recreate the activity).
 */
class DemoApp : Application() {
    val modelManager: ModelManager by lazy { DefaultModelManager(this, WorkManager.getInstance(this)) }

    private var stt: OnnxSpeechToText? = null
    private var guard: MemoryGuard? = null

    @Synchronized
    fun speech(): OnnxSpeechToText = stt ?: OnnxSpeechToText.create(modelManager).also {
        stt = it
        guard = MemoryGuard(it).also { g -> registerComponentCallbacks(g) }
    }

    /** Closes the engine; the next [speech] call builds a fresh one. */
    @Synchronized
    fun closeSpeech() {
        guard?.let { unregisterComponentCallbacks(it) }
        guard = null
        stt?.close()
        stt = null
    }
}
