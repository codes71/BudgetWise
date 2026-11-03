package app.budgetwise.updates

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal class InvalidUpdate(message: String) : IOException(message)

/** All untrusted release metadata is checked before it can choose a download or a cache filename. */
internal object UpdatePolicy {
    const val MAX_DESCRIPTOR_BYTES = 32 * 1024L
    const val MAX_APK_BYTES = 150 * 1024 * 1024L
    private val name = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
    private val digest = Regex("[a-fA-F0-9]{64}")

    fun metadataUrl(raw: String): HttpUrl = secureUrl(raw).also {
        requireValid(it.query == null && it.fragment == null, "Metadata URL must be fixed")
    }

    fun apkUrl(raw: String): HttpUrl = secureUrl(raw).also { url ->
        val segments = url.pathSegments
        requireValid(
            url.host == "github.com" && url.query == null && url.fragment == null &&
                segments.size == 6 && segments.take(4) == listOf("codes71", "BudgetWise", "releases", "download") &&
                name.matches(segments[4]) && name.matches(segments[5]) && segments[5].endsWith(".apk"),
            "APK must be a BudgetWise GitHub release asset",
        )
    }

    fun redirectUrl(raw: String): HttpUrl = secureUrl(raw).also { url ->
        requireValid(url.fragment == null, "Unexpected fragment")
        when (url.host) {
            "github.com" -> apkUrl(raw)
            "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> Unit
            else -> throw InvalidUpdate("Untrusted release redirect")
        }
    }

    private fun secureUrl(raw: String): HttpUrl {
        val url = raw.toHttpUrlOrNull() ?: throw InvalidUpdate("Invalid URL")
        requireValid(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty(), "HTTPS required")
        return url
    }

    /** Explicit release:null is the only valid empty feed. Missing release fields are rejected. */
    fun parse(bytes: ByteArray, applicationId: String): UpdateRelease? {
        requireValid(bytes.size <= MAX_DESCRIPTOR_BYTES, "Descriptor too large")
        try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            requireValid(number(json, "schemaVersion") == 1L, "Unknown schema")
            requireValid(string(json, "channel") == "friends", "Wrong channel")
            requireValid(string(json, "applicationId") == applicationId, "Wrong package")
            if (json.has("release")) {
                requireValid(json.isNull("release"), "Unexpected release wrapper")
                return null
            }
            val release = UpdateRelease(
                versionCode = number(json, "versionCode"),
                versionName = string(json, "versionName"),
                minSdk = number(json, "minSdk").let {
                    requireValid(it in 1..Int.MAX_VALUE.toLong(), "Invalid minimum SDK")
                    it.toInt()
                },
                apkUrl = string(json, "apkUrl"),
                sha256 = string(json, "sha256").lowercase(),
                sizeBytes = number(json, "sizeBytes"),
                notes = string(json, "notes"),
            )
            requireValid(release.versionCode > 0, "Invalid version")
            requireValid(release.versionName.isNotBlank() && release.versionName.length <= 80, "Invalid version name")
            requireValid(release.sizeBytes in 1..MAX_APK_BYTES, "Invalid APK size")
            requireValid(digest.matches(release.sha256), "Invalid SHA-256")
            requireValid(release.notes.length <= 4000, "Notes too long")
            apkUrl(release.apkUrl)
            return release
        } catch (e: InvalidUpdate) {
            throw e
        } catch (_: Exception) {
            throw InvalidUpdate("Malformed release descriptor")
        }
    }

    private fun string(json: JSONObject, key: String): String =
        (json.get(key) as? String) ?: throw InvalidUpdate("Invalid $key")

    private fun number(json: JSONObject, key: String): Long = when (val value = json.get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw InvalidUpdate("Invalid $key")
    }

    fun requireIdentity(
        release: UpdateRelease,
        applicationId: String,
        installedVersion: Long,
        sdk: Int,
        archivePackage: String?,
        archiveVersion: Long,
        archiveMinSdk: Int,
        installedSigners: Set<String>,
        archiveSigners: Set<String>,
    ) {
        requireValid(archivePackage == applicationId, "APK package differs")
        requireValid(archiveVersion == release.versionCode && archiveVersion > installedVersion, "APK version differs")
        requireValid(archiveMinSdk == release.minSdk && archiveMinSdk <= sdk, "APK minimum SDK differs")
        // Deliberately fail closed on key rotation. Only the exact current signer set is accepted.
        requireValid(installedSigners.isNotEmpty() && archiveSigners == installedSigners, "APK signer differs")
    }

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    /** Bounded streaming, including unknown/chunked lengths. The caller provides cancellation and progress. */
    fun copyVerified(
        input: InputStream,
        output: OutputStream,
        release: UpdateRelease,
        checkpoint: (Long) -> Unit,
    ) {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            checkpoint(copied)
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            copied += count
            requireValid(copied <= release.sizeBytes && copied <= MAX_APK_BYTES, "APK exceeds declared size")
            output.write(buffer, 0, count)
            hash.update(buffer, 0, count)
            checkpoint(copied)
        }
        requireValid(copied == release.sizeBytes, "APK is incomplete")
        requireValid(hash.digest().hex() == release.sha256, "APK hash differs")
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun requireValid(condition: Boolean, message: String) {
        if (!condition) throw InvalidUpdate(message)
    }
}
