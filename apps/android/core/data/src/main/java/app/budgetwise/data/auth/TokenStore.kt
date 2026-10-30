package app.budgetwise.data.auth

import java.time.Instant

/** The signed-in user as returned by the login endpoint. */
data class UserInfo(val id: String, val email: String, val fullName: String)

/** One account's session, as the store held it before several accounts (and as tests seed it). */
data class StoredSession(val token: String, val expiresAt: Instant, val user: UserInfo) {
    override fun toString() = "StoredSession(token=<redacted>, expiresAt=$expiresAt, user=$user)"
}

/** A token from POST /auth/token and the moment it stops working. */
data class AccessToken(val token: String, val expiresAt: Instant) {
    override fun toString() = "AccessToken(token=<redacted>, expiresAt=$expiresAt)"
}

/**
 * One account used on this phone. [access] is null once the token is gone (a 401, or dropped after sign-out); the
 * account itself stays, so its entries keep their owner and [check] keeps offline sign-in working. [signedOut] is set
 * by sign-out: the token is then kept only until the account's outbox has drained.
 */
data class SavedAccount(
    val user: UserInfo,
    val access: AccessToken?,
    val check: PasswordCheck? = null,
    val signedOut: Boolean = false,
)

/** Every account on this phone, and which one the screens show ([activeId], null when signed out). */
data class SavedAccounts(val activeId: String?, val accounts: List<SavedAccount>) {
    val active: SavedAccount? get() = activeId?.let(::byId)

    fun byId(id: String): SavedAccount? = accounts.firstOrNull { it.user.id == id }

    /** [account] in place of the saved one with the same user id, or added at the end. */
    fun with(account: SavedAccount): SavedAccounts {
        val i = accounts.indexOfFirst { it.user.id == account.user.id }
        return copy(accounts = if (i < 0) accounts + account else accounts.toMutableList().also { it[i] = account })
    }

    companion object {
        val EMPTY = SavedAccounts(activeId = null, accounts = emptyList())

        /** [session] as the only, active account. An empty token (the old expired marker) means no token. */
        fun of(session: StoredSession): SavedAccounts = SavedAccounts(
            activeId = session.user.id,
            accounts = listOf(
                SavedAccount(session.user, session.token.takeIf { it.isNotEmpty() }?.let { AccessToken(it, session.expiresAt) }),
            ),
        )
    }
}

/** Persistent home of the accounts and their sessions. Implementations must never log a token or a check. */
interface TokenStore {
    /** [SavedAccounts.EMPTY] when nothing is stored or the stored blob is unreadable (it is then cleared). */
    suspend fun load(): SavedAccounts

    suspend fun save(accounts: SavedAccounts)
}
