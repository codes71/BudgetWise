package app.budgetwise.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "budgetwise_session",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal const val SESSION_KEY_ALIAS = "app.budgetwise.session.v1"

/**
 * Every account on this phone (session, offline password check, sign-out mark) plus the active-account pointer, in one
 * blob encrypted with an AES-256/GCM key that lives in AndroidKeyStore. Stored as Base64(iv || ciphertext) in
 * Preferences DataStore, so a save replaces all of it at once. Tokens and checks are never logged.
 * A missing or invalidated key (restore to a new device, OS change) makes the blob undecryptable; [load] then clears it
 * and returns [SavedAccounts.EMPTY] so the user simply signs in again (online, since the offline checks went with it).
 * The single-session blob of earlier versions is read once and rewritten in the new format.
 */
@Singleton
class KeystoreTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : TokenStore {
    private val dataStore = context.applicationContext.sessionDataStore

    @Serializable
    private class AccountsBlob(val active: String? = null, val accounts: List<AccountBlob> = emptyList())

    @Serializable
    private class AccountBlob(
        val id: String,
        val email: String,
        val fullName: String,
        val token: String? = null,
        val expiresAtEpochMillis: Long? = null,
        val check: CheckBlob? = null,
        val signedOut: Boolean = false,
    )

    /** Salt and hash in Base64. */
    @Serializable
    private class CheckBlob(val salt: String, val hash: String, val iterations: Int)

    /** The format before several accounts: one session, an empty token once it was rejected. */
    @Serializable
    private class LegacyBlob(
        val token: String,
        val expiresAtEpochMillis: Long,
        val id: String,
        val email: String,
        val fullName: String,
    )

    override suspend fun save(accounts: SavedAccounts) {
        val plain = json.encodeToString(AccountsBlob.serializer(), accounts.toBlob()).toByteArray(Charsets.UTF_8)
        val encoded = try {
            encrypt(plain)
        } catch (e: Exception) {
            // Broken or invalidated key: replace it with a fresh one and retry once.
            deleteKey()
            encrypt(plain)
        }
        dataStore.edit {
            it[KEY_ACCOUNTS] = encoded
            it.remove(KEY_LEGACY_SESSION)
        }
    }

    override suspend fun load(): SavedAccounts {
        val prefs = dataStore.data.first()
        val encoded = prefs[KEY_ACCOUNTS]
        val legacy = prefs[KEY_LEGACY_SESSION]
        if (encoded == null && legacy == null) return SavedAccounts.EMPTY
        val accounts = try {
            if (encoded != null) {
                json.decodeFromString(AccountsBlob.serializer(), String(decrypt(encoded), Charsets.UTF_8)).toAccounts()
            } else {
                val b = json.decodeFromString(LegacyBlob.serializer(), String(decrypt(legacy!!), Charsets.UTF_8))
                SavedAccounts.of(StoredSession(b.token, Instant.ofEpochMilli(b.expiresAtEpochMillis), UserInfo(b.id, b.email, b.fullName)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Key invalidated or missing, corrupt blob, or schema drift: drop it and sign in again.
            deleteKey()
            clear()
            return SavedAccounts.EMPTY
        }
        if (encoded == null) {
            try {
                save(accounts)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The old blob stays and is read again next time; the session is not lost over a failed write.
            }
        }
        return accounts
    }

    /** Removes everything, the old single-session blob included. */
    internal suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_ACCOUNTS)
            it.remove(KEY_LEGACY_SESSION)
        }
    }

    private fun SavedAccounts.toBlob() = AccountsBlob(
        active = activeId,
        accounts = accounts.map { a ->
            AccountBlob(
                id = a.user.id,
                email = a.user.email,
                fullName = a.user.fullName,
                token = a.access?.token,
                expiresAtEpochMillis = a.access?.expiresAt?.toEpochMilli(),
                check = a.check?.let { CheckBlob(it.salt.base64(), it.hash.base64(), it.iterations) },
                signedOut = a.signedOut,
            )
        },
    )

    private fun AccountsBlob.toAccounts() = SavedAccounts(
        activeId = active,
        accounts = accounts.map { b ->
            SavedAccount(
                user = UserInfo(b.id, b.email, b.fullName),
                access = if (b.token != null && b.expiresAtEpochMillis != null) {
                    AccessToken(b.token, Instant.ofEpochMilli(b.expiresAtEpochMillis))
                } else {
                    null
                },
                check = b.check?.let { PasswordCheck(it.salt.unbase64(), it.hash.unbase64(), it.iterations) },
                signedOut = b.signedOut,
            )
        },
    )

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.unbase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val out = cipher.iv + cipher.doFinal(plain)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        require(raw.size > IV_BYTES) { "blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        return cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
    }

    private fun deleteKey() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // Nothing more to do; the next encrypt attempt surfaces any real failure.
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = SESSION_KEY_ALIAS
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val KEY_ACCOUNTS = stringPreferencesKey("accounts_v1")
        val KEY_LEGACY_SESSION = stringPreferencesKey("session_v1")
        val json = Json { ignoreUnknownKeys = true }
    }
}
