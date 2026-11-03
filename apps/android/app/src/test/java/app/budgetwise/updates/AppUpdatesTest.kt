package app.budgetwise.updates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AppUpdatesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val calls = AtomicInteger()
    private val empty = """{"schemaVersion":1,"channel":"friends","applicationId":"app.budgetwise","release":null}"""
    private var respond: (okhttp3.Request) -> Response = { request -> response(request, 200, empty) }
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        calls.incrementAndGet()
        assertEquals(null, chain.request().header("Authorization"))
        assertEquals(null, chain.request().header("Cookie"))
        respond(chain.request())
    }.build()

    @Before fun clearUpdaterState() {
        context.getSharedPreferences("friends_updates", 0).edit().clear().commit()
        java.io.File(context.cacheDir, "updates").deleteRecursively()
    }
    @After fun closeScope() { scope.cancel(); client.dispatcher.executorService.shutdown() }

    private fun updater(enabled: Boolean = true) = AppUpdates(context, enabled,
        "https://budgetwis3.vercel.app/updates/friends.json", scope, client)

    private fun response(request: okhttp3.Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build()

    private fun await(updates: AppUpdates, condition: (UpdateState) -> Boolean): UpdateState = try {
        runBlocking {
            withTimeout(5_000) {
                val state = updates.state.first(condition)
                scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
                state
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("Updater did not reach expected state; actual=${updates.state.value}", e)
    }

    private fun releaseJson(): String = JSONObject(empty).apply {
        remove("release"); put("versionCode", 999999); put("versionName", "test"); put("minSdk", 26)
        put("apkUrl", "https://github.com/codes71/BudgetWise/releases/download/test/app.apk")
        put("sha256", UpdatePolicy.hash("apk".toByteArray())); put("sizeBytes", 3); put("notes", "Test")
    }.toString()

    @Test fun disabledDistributionNeverChecksOrDownloads() {
        val updates = updater(false)
        updates.check(true); updates.download(); updates.cancelDownload()
        assertEquals(UpdateState.Disabled, updates.state.value)
        assertEquals(0, calls.get())
    }

    @Test fun explicitEmptyFeedAnd404AreSafeCurrentStates() {
        val updates = updater()
        updates.check(true)
        await(updates) { it == UpdateState.Current }
        respond = { response(it, 404, "missing") }
        updates.check(true)
        await(updates) { it == UpdateState.Current }
        assertTrue(calls.get() >= 1)
    }

    @Test fun automaticCheckIsThrottledButExplicitRetryIsNot() {
        val updates = updater()
        updates.check()
        await(updates) { it == UpdateState.Current }
        updates.check()
        assertEquals(1, calls.get())
        respond = { throw IOException("offline") }
        updates.check(true)
        await(updates) { it is UpdateState.Problem }
        assertEquals(2, calls.get())
        assertEquals(UpdateProblem.OFFLINE, (updates.state.value as UpdateState.Problem).problem)
    }

    @Test fun failedManualRecheckRetainsKnownReleaseForRetry() {
        respond = { response(it, 200, releaseJson()) }
        val updates = updater()
        updates.check(true)
        val available = await(updates) { it is UpdateState.Available } as UpdateState.Available
        respond = { throw IOException("offline") }
        updates.check(true)
        val failed = await(updates) { it is UpdateState.Problem } as UpdateState.Problem
        assertEquals(available.release, failed.release)
    }

    @Test fun downloadRejectsBytesThatMatchHashButAreNotAnApk() {
        respond = { request -> response(request, 200, if (request.url.host == "github.com") "apk" else releaseJson()) }
        val updates = updater()
        updates.check(true)
        await(updates) { it is UpdateState.Available }
        updates.download()
        val failure = await(updates) { it is UpdateState.Problem } as UpdateState.Problem
        assertEquals(UpdateProblem.INVALID_APK, failure.problem)
        assertTrue(java.io.File(context.cacheDir, "updates").listFiles().orEmpty().none { it.extension == "apk" || it.extension == "part" })
    }

    @Test fun metadataRedirectNeverContactsRedirectHost() {
        respond = { response(it, 302, "").newBuilder().header("Location", "https://evil.test/feed").build() }
        val updates = updater()
        updates.check(true)
        val failure = await(updates) { it is UpdateState.Problem } as UpdateState.Problem
        assertEquals(UpdateProblem.INVALID_RELEASE, failure.problem)
        assertEquals(1, calls.get())
    }

    @Test fun duplicateDownloadIsIgnoredAndCancellationReturnsToAvailable() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        respond = { request ->
            if (request.url.host == "github.com") {
                started.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                response(request, 200, "apk")
            } else response(request, 200, releaseJson())
        }
        val updates = updater()
        updates.check(true)
        await(updates) { it is UpdateState.Available }
        try {
            updates.download()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            updates.download()
            assertEquals(2, calls.get()) // One descriptor and one APK, despite the second Download tap.
            updates.cancelDownload()
            assertTrue(updates.state.value is UpdateState.Available)
        } finally {
            finish.countDown()
        }
        await(updates) { it is UpdateState.Available }
        assertTrue(java.io.File(context.cacheDir, "updates").listFiles().orEmpty().none { it.extension == "part" })
    }

    @Test fun untrustedApkRedirectIsRejectedBeforeRequestingDestination() {
        respond = { request ->
            if (request.url.host == "github.com") response(request, 302, "").newBuilder()
                .header("Location", "https://evil.test/app.apk").build()
            else response(request, 200, releaseJson())
        }
        val updates = updater()
        updates.check(true)
        await(updates) { it is UpdateState.Available }
        updates.download()
        val failure = await(updates) { it is UpdateState.Problem } as UpdateState.Problem
        assertEquals(UpdateProblem.INVALID_APK, failure.problem)
        assertEquals(2, calls.get())
    }

    @Test
    @Config(sdk = [27])
    @Suppress("DEPRECATION")
    fun permissionReturnCannotBeConsumedByAnAutomaticCheckOrLoopOnDenial() {
        val pm = context.packageManager
        val shadowPm = shadowOf(pm)
        val signer = Signature("release-cert".toByteArray())
        // Preserve the manifest's FileProvider while replacing the installed signer/version in the shadow.
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA).apply {
            versionCode = 1
            signatures = arrayOf(signer)
        }
        shadowPm.installPackage(installed)
        val archive = PackageInfo().apply {
            packageName = context.packageName
            versionCode = 999999
            signatures = arrayOf(signer)
            applicationInfo = ApplicationInfo().apply { minSdkVersion = 26 }
        }
        // Robolectric's archive parser mapping lets this exercise identity validation without a generated APK.
        val apkHash = UpdatePolicy.hash("apk".toByteArray())
        shadowPm.setPackageArchiveInfo(java.io.File(context.cacheDir, "updates/999999-$apkHash.apk").absolutePath, archive)
        respond = { request -> response(request, 200, if (request.url.host == "github.com") "apk" else releaseJson()) }
        val updates = updater()
        updates.check(true)
        await(updates) { it is UpdateState.Available }
        // Download parses the temporary file before atomic rename, so map that filename when it exists too.
        // A pre-existing verified cache exercises the process-restart recovery path directly.
        val folder = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
        java.io.File(folder, "999999-$apkHash.apk").writeText("apk")
        java.io.File(folder, "ready.json").writeText(releaseJson())
        val restored = updater()
        restored.check()
        await(restored) { it is UpdateState.Ready }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowPm.setCanRequestPackageInstalls(false)
        restored.install(activity)
        await(restored) { it is UpdateState.Problem }
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(activity).nextStartedActivity.action)
        restored.check() // MainActivity.onStart when Android's permission screen closes.
        restored.onResume(activity) // Permission denied: retain explicit retry, never reopen settings.
        assertEquals(null, shadowOf(activity).nextStartedActivity)
        assertEquals(UpdateProblem.PERMISSION_NEEDED, (restored.state.value as UpdateState.Problem).problem)

        restored.install(activity) // User explicitly taps Allow installation again.
        await(restored) { it is UpdateState.Problem }
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(activity).nextStartedActivity.action)
        restored.check()
        shadowPm.setCanRequestPackageInstalls(true)
        restored.onResume(activity)
        await(restored) { it is UpdateState.Ready }
        assertEquals(Intent.ACTION_VIEW, shadowOf(activity).nextStartedActivity.action)
        restored.onResume(activity)
        assertEquals(null, shadowOf(activity).nextStartedActivity)
        activity.finish()
    }

    @Test fun currentVersionCleansOnlyUpdaterOwnedObsoleteCacheFiles() {
        val folder = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
        val obsolete = java.io.File(folder, "0-${"0".repeat(64)}.apk").apply { writeText("obsolete") }
        val unrelated = java.io.File(folder, "keep-this.txt").apply { writeText("unrelated") }
        val updates = updater()
        updates.check(true)
        await(updates) { it == UpdateState.Current }
        assertTrue(!obsolete.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun retryPrunesProcessDeathPartialsEvenWhenTheNextDownloadFails() {
        respond = { response(it, 200, releaseJson()) }
        val updates = updater()
        updates.check(true)
        await(updates) { it is UpdateState.Available }
        val folder = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
        val interrupted = java.io.File(folder, "01234567-89ab-cdef-0123-456789abcdef.part").apply { writeText("old incomplete APK") }
        val interruptedMetadata = java.io.File(folder, "ready.json.part").apply { writeText("interrupted") }
        val unrelated = java.io.File(folder, "keep-this.txt").apply { writeText("unrelated") }
        respond = { response(it, 503, "unavailable") }
        updates.download()
        await(updates) { it is UpdateState.Problem }
        assertTrue(!interrupted.exists())
        assertTrue(!interruptedMetadata.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun failedProcessRestartRecoveryRemovesUnverifiedApksAndKeepsUnrelatedFiles() {
        val folder = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
        val apkHash = UpdatePolicy.hash("apk".toByteArray())
        val invalid = java.io.File(folder, "999999-$apkHash.apk").apply { writeText("bad") }
        val metadata = java.io.File(folder, "ready.json").apply { writeText(releaseJson()) }
        val unrelated = java.io.File(folder, "keep-this.txt").apply { writeText("unrelated") }
        respond = { response(it, 200, releaseJson()) }
        val restarted = updater()
        restarted.check()
        await(restarted) { it is UpdateState.Available }
        assertTrue(!invalid.exists())
        assertTrue(!metadata.exists())
        assertTrue(unrelated.exists())
    }
}
