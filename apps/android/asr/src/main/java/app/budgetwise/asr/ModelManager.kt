package app.budgetwise.asr

import app.budgetwise.asr.engine.VoiceFiles
import kotlinx.coroutines.flow.StateFlow

/** Downloads and verifies the [VoicePack]. */
interface ModelManager {
    val state: StateFlow<ModelState>

    /**
     * Starts a background download of the pack unless it is already verified. Returns without waiting;
     * progress is reported through [state].
     */
    suspend fun ensureDownloaded(allowMetered: Boolean = false)

    /** Cancels any download and deletes the pack's files. */
    suspend fun delete()

    /** Non-null only when every file of the pack is fully downloaded and verified. */
    fun files(): VoiceFiles?
}
