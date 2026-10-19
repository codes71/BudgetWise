package app.budgetwise.asr

import app.budgetwise.asr.engine.ModelFiles
import kotlinx.coroutines.flow.StateFlow

interface ModelManager {
    /** State of [activeVariant]. */
    val state: StateFlow<ModelState>
    val activeVariant: StateFlow<RajVariant?>

    /**
     * Makes [variant] active and starts a background download unless it is already verified.
     * Returns without waiting; progress is reported through [state].
     */
    suspend fun ensureDownloaded(variant: RajVariant, allowMetered: Boolean = false)

    /** Cancels any download of [variant] and deletes its files. Other variants are untouched. */
    suspend fun delete(variant: RajVariant)

    /** Non-null only when the files for [variant] are fully downloaded and verified. */
    fun filesFor(variant: RajVariant): ModelFiles?
}
