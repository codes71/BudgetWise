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
 * Session blob (token, expiry, user) encrypted with an AES-256/GCM key that lives in AndroidKeyStore.
 * Stored as Base64(iv || ciphertext) in Preferences DataStore. The token is never logged.
 * A missing or invalidated key (restore to a new device, OS change) makes the blob undecryptable;
 * [load] then clears it and returns null so the user simply signs in again.
 */
@Singleton
class KeystoreTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : TokenStore {
    private val dataStore = context.applicationContext.sessionDataStore

    @Serializable
    private class Blob(
        val token: String,
        val expiresAtEpochMillis: Long,
        val id: String,
        val email: String,
        val fullName: String,
    )

    override suspend fun save(token: String, expiresAt: Instant, user: UserInfo) {
        val blob = Blob(token, expiresAt.toEpochMilli(), user.id, user.email, user.fullName)
        val plain = Json.encodeToString(Blob.serializer(), blob).toByteArray(Charsets.UTF_8)
        val encoded = try {
            encrypt(plain)
        } catch (e: Exception) {
            // Broken or invalidated key: replace it with a fresh one and retry once.
            deleteKey()
            encrypt(plain)
        }
        dataStore.edit { it[KEY_SESSION] = encoded }
    }

    override suspend fun load(): StoredSession? {
        val encoded = dataStore.data.first()[KEY_SESSION] ?: return null
        return try {
            val json = String(decrypt(encoded), Charsets.UTF_8)
            val b = Json.decodeFromString(Blob.serializer(), json)
            StoredSession(b.token, Instant.ofEpochMilli(b.expiresAtEpochMillis), UserInfo(b.id, b.email, b.fullName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Key invalidated or missing, corrupt blob, or schema drift: drop it and sign in again.
            deleteKey()
            clear()
            null
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_SESSION) }
    }

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
        val KEY_SESSION = stringPreferencesKey("session_v1")
    }
}
