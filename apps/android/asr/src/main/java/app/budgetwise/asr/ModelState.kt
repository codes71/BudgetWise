package app.budgetwise.asr

sealed interface ModelState {
    data object Missing : ModelState

    /**
     * [waiting] while the download is not running: it waits for its network, or for a retry after a dropped
     * connection. [wifiOnly] when it may only run on Wi-Fi (an unmetered network); the user has not chosen mobile data.
     */
    data class Downloading(val progress: Float, val waiting: Boolean = false, val wifiOnly: Boolean = false) : ModelState

    /** [path] is the pack directory holding every model file. */
    data class Ready(val path: String) : ModelState

    data class Failed(val reason: String) : ModelState {
        /** The phone has no room for the pack; another try only helps after the user frees some. */
        val outOfStorage: Boolean get() = reason == NOT_ENOUGH_STORAGE
    }

    companion object {
        const val NOT_ENOUGH_STORAGE = "Not enough free storage to download the ${VoicePack.DISPLAY_NAME}"
    }
}
