package app.budgetwise.data.auth

import java.time.Instant

/** The signed-in user as returned by the login endpoint. */
data class UserInfo(val id: String, val email: String, val fullName: String)

data class StoredSession(val token: String, val expiresAt: Instant, val user: UserInfo) {
    override fun toString() = "StoredSession(token=<redacted>, expiresAt=$expiresAt, user=$user)"
}

/** Persistent home of the session. Implementations must never log the token. */
interface TokenStore {
    suspend fun save(token: String, expiresAt: Instant, user: UserInfo)

    /** Returns null when nothing is stored or the stored blob is unreadable (it is then cleared). */
    suspend fun load(): StoredSession?

    suspend fun clear()
}
