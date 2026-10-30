package app.budgetwise.asr

sealed class AsrException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ModelNotReady(val state: ModelState) : AsrException("model not ready: $state")

    class EngineFailure(val code: Int, detail: String) : AsrException(detail)
}
