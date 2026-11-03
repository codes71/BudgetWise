package app.budgetwise.updates

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.budgetwise.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Friends-distribution updater. It never touches the ledger, sessions, or the application's authenticated client. */
@Singleton
class AppUpdates internal constructor(
    private val context: Context,
    val enabled: Boolean,
    private val manifestUrl: String,
    private val scope: CoroutineScope,
    client: OkHttpClient,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.applicationContext,
        BuildConfig.DIRECT_UPDATES,
        BuildConfig.UPDATE_MANIFEST_URL,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build(),
    )

    private val mutableState = MutableStateFlow<UpdateState>(if (enabled) UpdateState.Idle else UpdateState.Disabled)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private val http = UpdateHttp(client.newBuilder().followRedirects(false).followSslRedirects(false).build())
    private val preferences by lazy { context.getSharedPreferences("friends_updates", Context.MODE_PRIVATE) }
    private val directory get() = File(context.cacheDir, "updates")
    private val descriptor get() = File(directory, "ready.json")
    private var release: UpdateRelease? = null
    private var operation: Operation? = null
    private var job: Job? = null
    private var generation = 0L
    private var awaitingPermission = false
    private val lock = Any()

    private enum class Operation { CHECK, DOWNLOAD, INSTALL }

    /** Automatic attempts are throttled even after failure. A user's explicit check can always retry. */
    fun check(force: Boolean = false) {
        if (!enabled) return
        // Returning from Android settings must not start a competing check before the pending Install resumes.
        if (!force && (awaitingPermission || mutableState.value is UpdateState.Ready ||
                (mutableState.value as? UpdateState.Problem)?.problem == UpdateProblem.PERMISSION_NEEDED)
        ) return
        run(Operation.CHECK) { token ->
            if (!force && mutableState.value == UpdateState.Idle && restoreReady(token)) return@run
            val now = System.currentTimeMillis()
            val last = preferences.getLong("last_attempt", 0)
            if (!force && last > 0 && now >= last && now - last < TimeUnit.HOURS.toMillis(24)) return@run
            preferences.edit().putLong("last_attempt", now).apply()
            setState(token, UpdateState.Checking)
            try {
                if (manifestUrl.isBlank()) {
                    setState(token, UpdateState.Problem(UpdateProblem.NOT_CONFIGURED, release))
                    return@run
                }
                val url = UpdatePolicy.metadataUrl(manifestUrl)
                val candidate = withContext(Dispatchers.IO) {
                    http.get(url, apk = false).use { response ->
                        if (response.code == 404 || response.code == 204) return@use null
                        if (!response.isSuccessful) throw IOException("Update check failed")
                        UpdatePolicy.parse(http.descriptor(response), context.packageName)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (candidate == null || candidate.versionCode <= installedVersion()) {
                    release = null
                    withContext(Dispatchers.IO) { cleanupCache(installedVersion = installedVersion()) }
                    setState(token, UpdateState.Current)
                } else if (candidate.minSdk > Build.VERSION.SDK_INT) {
                    setState(token, UpdateState.Problem(UpdateProblem.INVALID_RELEASE, candidate))
                } else {
                    release = candidate
                    setState(token, if (readyFile(candidate).exists() && verifyCached(candidate)) UpdateState.Ready(candidate) else UpdateState.Available(candidate))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: InvalidUpdate) {
                setState(token, UpdateState.Problem(UpdateProblem.INVALID_RELEASE, release))
            } catch (_: Exception) {
                setState(token, UpdateState.Problem(UpdateProblem.OFFLINE, release))
            }
        }
    }

    fun download() {
        if (!enabled) return
        val candidate = release ?: return
        run(Operation.DOWNLOAD) { token ->
            setState(token, UpdateState.Downloading(candidate, 0, candidate.sizeBytes))
            val partial = File(directory, "${UUID.randomUUID()}.part")
            try {
                val metadata = withContext(Dispatchers.IO) {
                    check(directory.mkdirs() || directory.isDirectory)
                    pruneInterruptedDownloads()
                    http.get(UpdatePolicy.apkUrl(candidate.apkUrl), apk = true).use { response ->
                        if (!response.isSuccessful) throw IOException("Download failed")
                        val body = response.body ?: throw IOException("Missing APK")
                        if (body.contentLength() != -1L && body.contentLength() != candidate.sizeBytes) throw InvalidUpdate("APK length differs")
                        val coroutine = currentCoroutineContext()
                        FileOutputStream(partial).use { output ->
                            body.byteStream().use { input ->
                                UpdatePolicy.copyVerified(input, output, candidate) { copied ->
                                    coroutine.ensureActive()
                                    setState(token, UpdateState.Downloading(candidate, copied, candidate.sizeBytes))
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    verifyArchive(partial, candidate)
                    currentCoroutineContext().ensureActive()
                    if (!partial.renameTo(readyFile(candidate))) throw IOException("Cannot finish APK")
                    encode(candidate)
                }
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) {
                    val pending = File(directory, "ready.json.part")
                    pending.writeText(metadata)
                    if (!pending.renameTo(descriptor)) throw IOException("Cannot save update state")
                    cleanupCache(keep = readyFile(candidate))
                }
                setState(token, UpdateState.Ready(candidate))
            } catch (e: CancellationException) {
                throw e
            } catch (_: InvalidUpdate) {
                setState(token, UpdateState.Problem(UpdateProblem.INVALID_APK, candidate))
            } catch (_: Exception) {
                setState(token, UpdateState.Problem(UpdateProblem.DOWNLOAD_FAILED, candidate))
            } finally {
                partial.delete()
            }
        }
    }

    fun cancelDownload() {
        synchronized(lock) {
            if (operation != Operation.DOWNLOAD || job?.isActive != true) return
            generation++
            http.cancel()
            job?.cancel()
            release?.let { mutableState.value = UpdateState.Available(it) }
        }
    }

    /** Called only by an explicit Install action. Android still asks the user to confirm installation. */
    fun install(activity: Activity) {
        if (!enabled) return
        val candidate = release ?: return
        if (mutableState.value !is UpdateState.Ready &&
            (mutableState.value as? UpdateState.Problem)?.problem !in listOf(UpdateProblem.PERMISSION_NEEDED, UpdateProblem.INSTALL_FAILED)
        ) return
        run(Operation.INSTALL) { token ->
            try {
                withContext(Dispatchers.IO) { verifyFile(readyFile(candidate), candidate) }
                if (activity.isFinishing || activity.isDestroyed) return@run
                if (!context.packageManager.canRequestPackageInstalls()) {
                    awaitingPermission = true
                    setState(token, UpdateState.Problem(UpdateProblem.PERMISSION_NEEDED, candidate))
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    return@run
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", readyFile(candidate))
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri("BudgetWise update", uri)
                activity.startActivity(intent)
                setState(token, UpdateState.Ready(candidate))
            } catch (e: CancellationException) {
                throw e
            } catch (_: InvalidUpdate) {
                awaitingPermission = false
                setState(token, UpdateState.Problem(UpdateProblem.INVALID_APK, candidate))
            } catch (_: Exception) {
                awaitingPermission = false
                setState(token, UpdateState.Problem(UpdateProblem.INSTALL_FAILED, candidate))
            }
        }
    }

    /** Resume an already explicit installation request once. Denial never opens settings again by itself. */
    fun onResume(activity: Activity) {
        if (!enabled || !awaitingPermission) return
        awaitingPermission = false
        if (context.packageManager.canRequestPackageInstalls()) install(activity)
        else release?.let { mutableState.value = UpdateState.Problem(UpdateProblem.PERMISSION_NEEDED, it) }
    }

    private fun run(kind: Operation, block: suspend (Long) -> Unit) {
        synchronized(lock) {
            if (job?.isActive == true) return
            val token = ++generation
            operation = kind
            job = scope.launch(start = CoroutineStart.LAZY) { block(token) }
            job?.start()
        }
    }

    private fun setState(token: Long, next: UpdateState) {
        synchronized(lock) { if (token == generation) mutableState.value = next }
    }

    private suspend fun restoreReady(token: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            pruneInterruptedDownloads()
            if (!descriptor.isFile || descriptor.length() > UpdatePolicy.MAX_DESCRIPTOR_BYTES) {
                discardInvalidCache()
                return@withContext false
            }
            val candidate = UpdatePolicy.parse(descriptor.readBytes(), context.packageName)
            if (candidate == null || candidate.versionCode <= installedVersion()) {
                discardInvalidCache()
                return@withContext false
            }
            verifyFile(readyFile(candidate), candidate)
            currentCoroutineContext().ensureActive()
            cleanupCache(keep = readyFile(candidate))
            release = candidate
            setState(token, UpdateState.Ready(candidate))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            discardInvalidCache()
            false
        }
    }

    private suspend fun verifyCached(candidate: UpdateRelease): Boolean = withContext(Dispatchers.IO) {
        try { verifyFile(readyFile(candidate), candidate); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    private suspend fun verifyFile(file: File, candidate: UpdateRelease) {
        if (!file.isFile || file.length() != candidate.sizeBytes) throw InvalidUpdate("Cached APK missing")
        val coroutine = currentCoroutineContext()
        file.inputStream().use { input ->
            UpdatePolicy.copyVerified(input, object : java.io.OutputStream() {
                override fun write(b: Int) = Unit
                override fun write(b: ByteArray, off: Int, len: Int) = Unit
            }, candidate) { coroutine.ensureActive() }
        }
        verifyArchive(file, candidate)
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, candidate: UpdateRelease) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw InvalidUpdate("Unreadable APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        UpdatePolicy.requireIdentity(candidate, context.packageName, version(installed), Build.VERSION.SDK_INT,
            archive.packageName, version(archive), archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
            signers(installed), signers(archive))
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { UpdatePolicy.hash(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    @Suppress("DEPRECATION")
    private fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun installedVersion(): Long = version(context.packageManager.getPackageInfo(context.packageName, 0))

    private fun readyFile(candidate: UpdateRelease) = File(directory, "${candidate.versionCode}-${candidate.sha256}.apk")

    private val apkCacheName = Regex("([0-9]+)-[a-f0-9]{64}\\.apk")
    private val partialCacheName = Regex("[0-9a-f-]{36}\\.part")

    /** A killed process cannot run finally. Prune its bounded-but-incomplete files before allocating another APK. */
    private fun pruneInterruptedDownloads() {
        directory.listFiles().orEmpty().forEach { file ->
            if (partialCacheName.matches(file.name) || file.name == "ready.json.part") file.delete()
        }
    }

    private fun discardInvalidCache() {
        pruneInterruptedDownloads()
        directory.listFiles().orEmpty().forEach { file ->
            if (apkCacheName.matches(file.name) || file.name == "ready.json") file.delete()
        }
    }

    /** Delete only updater-owned filenames in its private cache, after a newer APK is safe or an update is installed. */
    private fun cleanupCache(keep: File? = null, installedVersion: Long? = null) {
        pruneInterruptedDownloads()
        directory.listFiles().orEmpty().forEach { file ->
            val version = apkCacheName.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            val obsoleteApk = version != null && file != keep &&
                (keep != null || (installedVersion != null && version <= installedVersion))
            if (obsoleteApk) file.delete()
        }
        if (installedVersion != null && descriptor.isFile && descriptor.length() <= UpdatePolicy.MAX_DESCRIPTOR_BYTES) {
            try {
                val saved = UpdatePolicy.parse(descriptor.readBytes(), context.packageName)
                if (saved == null || saved.versionCode <= installedVersion) descriptor.delete()
            } catch (_: Exception) {
                descriptor.delete()
            }
        }
    }

    private fun encode(candidate: UpdateRelease): String = org.json.JSONObject().apply {
        put("schemaVersion", 1); put("channel", "friends"); put("applicationId", context.packageName)
        put("versionCode", candidate.versionCode); put("versionName", candidate.versionName); put("minSdk", candidate.minSdk)
        put("apkUrl", candidate.apkUrl); put("sha256", candidate.sha256); put("sizeBytes", candidate.sizeBytes); put("notes", candidate.notes)
    }.toString()
}
