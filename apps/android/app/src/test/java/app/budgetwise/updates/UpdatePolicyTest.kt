package app.budgetwise.updates

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdatePolicyTest {
    private val apk = "signed APK bytes".toByteArray()
    private fun descriptor() = JSONObject().apply {
        put("schemaVersion", 1); put("channel", "friends"); put("applicationId", "app.budgetwise")
        put("versionCode", 2); put("versionName", "0.2.0"); put("minSdk", 26)
        put("apkUrl", "https://github.com/codes71/BudgetWise/releases/download/android-v0.2.0/budgetwise.apk")
        put("sha256", UpdatePolicy.hash(apk)); put("sizeBytes", apk.size); put("notes", "Updates")
    }
    private fun parse(json: JSONObject = descriptor()) = UpdatePolicy.parse(json.toString().toByteArray(), "app.budgetwise")
    private val release get() = requireNotNull(parse())

    @Test fun acceptsPinnedReleaseAndExplicitEmptyFeed() {
        assertEquals(2L, release.versionCode)
        assertNull(parse(JSONObject("""{"schemaVersion":1,"channel":"friends","applicationId":"app.budgetwise","release":null}""")))
    }

    @Test fun missingFlatFieldsAreNotAnEmptyFeed() {
        val json = descriptor().apply { remove("sha256") }
        assertThrows(InvalidUpdate::class.java) { parse(json) }
    }

    @Test fun refusesUnknownSchemaChannelAndApplication() {
        for ((key, value) in listOf("schemaVersion" to 2, "channel" to "play", "applicationId" to "other.app")) {
            assertThrows(InvalidUpdate::class.java) { parse(descriptor().put(key, value)) }
        }
    }

    @Test fun refusesMalformedNumbersAndOversizeMetadata() {
        for ((key, value) in listOf("versionCode" to "2", "versionCode" to 1.5, "minSdk" to -1, "sizeBytes" to UpdatePolicy.MAX_APK_BYTES + 1)) {
            assertThrows(InvalidUpdate::class.java) { parse(descriptor().put(key, value)) }
        }
        assertThrows(InvalidUpdate::class.java) { UpdatePolicy.parse(ByteArray(32 * 1024 + 1), "app.budgetwise") }
    }

    @Test fun refusesForeignRepositoryCredentialsNonHttpsAndNonApkAssets() {
        for (url in listOf(
            "http://github.com/codes71/BudgetWise/releases/download/v2/app.apk",
            "https://github.com/other/BudgetWise/releases/download/v2/app.apk",
            "https://github.com/codes71/Other/releases/download/v2/app.apk",
            "https://github.com.evil.test/codes71/BudgetWise/releases/download/v2/app.apk",
            "https://user:pass@github.com/codes71/BudgetWise/releases/download/v2/app.apk",
            "https://github.com:444/codes71/BudgetWise/releases/download/v2/app.apk",
            "https://github.com/codes71/BudgetWise/releases/download/v2/app.zip",
            "https://github.com/codes71/BudgetWise/releases/download/v2/app.apk?download=1",
            "https://github.com/codes71/BudgetWise/releases/download/v2/other%2Fapp.apk",
        )) assertThrows(url, InvalidUpdate::class.java) { UpdatePolicy.apkUrl(url) }
    }

    @Test fun allowsOnlyHttpsGithubAssetRedirectHosts() {
        UpdatePolicy.redirectUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1/asset?sig=abc")
        for (url in listOf("https://evil.test/app.apk", "http://release-assets.githubusercontent.com/a", "https://release-assets.githubusercontent.com.evil.test/a")) {
            assertThrows(InvalidUpdate::class.java) { UpdatePolicy.redirectUrl(url) }
        }
    }

    @Test fun streamsExactlyDeclaredBytesAndDigest() {
        val output = ByteArrayOutputStream()
        val checkpoints = mutableListOf<Long>()
        UpdatePolicy.copyVerified(ByteArrayInputStream(apk), output, release) { checkpoints += it }
        assertEquals(apk.toList(), output.toByteArray().toList())
        assertEquals(apk.size.toLong(), checkpoints.last())
    }

    @Test fun refusesTruncatedOversizeAndChangedDownloads() {
        for (bytes in listOf(apk.dropLast(1).toByteArray(), apk + byteArrayOf(0), ByteArray(apk.size))) {
            assertThrows(InvalidUpdate::class.java) {
                UpdatePolicy.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), release) {}
            }
        }
    }

    @Test fun cancellationStopsStreamingBeforeWritingMoreBytes() {
        val large = ByteArray(128 * 1024)
        val candidate = release.copy(sizeBytes = large.size.toLong(), sha256 = UpdatePolicy.hash(large))
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            UpdatePolicy.copyVerified(ByteArrayInputStream(large), output, candidate) { if (it >= 64 * 1024) throw CancellationException() }
        }
        assertEquals(64 * 1024, output.size())
    }

    private fun identity(
        application: String = "app.budgetwise", version: Long = 2, minimum: Int = 26,
        installed: Set<String> = setOf("release-cert"), archive: Set<String> = installed,
    ) = UpdatePolicy.requireIdentity(release, "app.budgetwise", 1, 35, application, version, minimum, installed, archive)

    @Test fun acceptsSamePackageNewerVersionAndExactSignerSet() = identity()

    @Test fun refusesForeignPackageVersionMismatchDowngradeAndWrongMinimumSdk() {
        assertThrows(InvalidUpdate::class.java) { identity(application = "other.app") }
        assertThrows(InvalidUpdate::class.java) { identity(version = 1) }
        assertThrows(InvalidUpdate::class.java) { identity(version = 3) }
        assertThrows(InvalidUpdate::class.java) { identity(minimum = 36) }
        assertThrows(InvalidUpdate::class.java) { identity(minimum = 25) }
    }

    @Test fun refusesUnsignedForeignAndRotatedSignerSets() {
        assertThrows(InvalidUpdate::class.java) { identity(archive = emptySet()) }
        assertThrows(InvalidUpdate::class.java) { identity(archive = setOf("debug-cert")) }
        assertThrows(InvalidUpdate::class.java) { identity(archive = setOf("release-cert", "new-cert")) }
        assertThrows(InvalidUpdate::class.java) { identity(installed = emptySet()) }
    }
}
