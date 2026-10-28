package app.budgetwise.asr

import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * Frees the loaded Raj engine when the system asks the app to trim memory in the background.
 * Register it with `Application.registerComponentCallbacks`. The next transcribe reopens the engine.
 */
class MemoryGuard(private val stt: RajSpeechToText) : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) stt.release()
    }

    override fun onLowMemory() {
        stt.release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}
