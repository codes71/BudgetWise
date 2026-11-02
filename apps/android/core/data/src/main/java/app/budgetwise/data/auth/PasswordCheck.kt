package app.budgetwise.data.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The offline password check of one account: a salted PBKDF2-SHA256 hash, never the password itself. Saved on every
 * successful online sign-in, so the same password can sign that account in on this phone without the internet.
 * [matches] compares in constant time. Deriving takes a noticeable moment on a phone: call it off the main thread.
 */
class PasswordCheck(val salt: ByteArray, val hash: ByteArray, val iterations: Int) {
    fun matches(password: String): Boolean {
        if (password.isEmpty() || iterations < 1 || hash.isEmpty()) return false
        return MessageDigest.isEqual(derive(password, salt, iterations, hash.size), hash)
    }

    override fun equals(other: Any?) =
        other is PasswordCheck && iterations == other.iterations && salt.contentEquals(other.salt) && hash.contentEquals(other.hash)

    override fun hashCode() = 31 * (31 * salt.contentHashCode() + hash.contentHashCode()) + iterations

    override fun toString() = "PasswordCheck(<redacted>, iterations=$iterations)"

    companion object {
        const val ITERATIONS = 120_000
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"

        /** A fresh random salt each time, so the same password never gives the same check twice. */
        fun create(password: String, random: SecureRandom = SecureRandom()): PasswordCheck {
            require(password.isNotEmpty()) { "password is empty" }
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            return PasswordCheck(salt, derive(password, salt, ITERATIONS, HASH_BYTES), ITERATIONS)
        }

        private fun derive(password: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
            val chars = password.toCharArray()
            val spec = PBEKeySpec(chars, salt, iterations, bytes * 8)
            return try {
                SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
                chars.fill('\u0000')
            }
        }
    }
}
