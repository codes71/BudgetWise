package app.budgetwise.ui.voice

internal const val VOICE_WAVEFORM_BARS = 12

/**
 * Retains only observed loudness changes, never synthetic samples. StateFlow conflates equal levels, so there is
 * deliberately no timer pretending to recover those samples. Observed silence clears the history to a quiet rest.
 */
internal fun observedWaveform(history: List<Float>, level: Float): List<Float> {
    val observed = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
    if (observed == 0f) return emptyList()
    return (history.takeLast(VOICE_WAVEFORM_BARS - 1) + observed)
}
