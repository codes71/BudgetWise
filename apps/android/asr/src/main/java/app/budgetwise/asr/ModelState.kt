package app.budgetwise.asr

sealed interface ModelState {
    data object Missing : ModelState

    data class Downloading(val progress: Float) : ModelState

    /** [path] is the pack directory holding every model file. */
    data class Ready(val path: String) : ModelState

    data class Failed(val reason: String) : ModelState
}
