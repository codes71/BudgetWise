package app.budgetwise.updates

/** Immutable release descriptor. Downloads and APK identity are independently verified before installation. */
data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

enum class UpdateProblem { OFFLINE, INVALID_RELEASE, DOWNLOAD_FAILED, INVALID_APK, INSTALL_FAILED, PERMISSION_NEEDED, NOT_CONFIGURED }

sealed interface UpdateState {
    data object Disabled : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val downloadedBytes: Long, val totalBytes: Long) : UpdateState
    data class Ready(val release: UpdateRelease) : UpdateState
    data class Problem(val problem: UpdateProblem, val release: UpdateRelease? = null) : UpdateState
}
