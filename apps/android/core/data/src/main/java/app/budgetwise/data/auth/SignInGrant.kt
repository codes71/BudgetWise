package app.budgetwise.data.auth

import app.budgetwise.data.net.TokenResponse
import java.time.Instant
import java.time.format.DateTimeParseException

/** What a successful POST /auth/token gives: the token, when it stops working, and the user. */
internal class SignInGrant(val token: String, val expiresAt: Instant, val user: UserInfo) {
    override fun toString() = "SignInGrant(token=<redacted>, expiresAt=$expiresAt, user=$user)"
}

/** Null when `expiresAt` is not an ISO instant: the answer cannot be used. */
internal fun TokenResponse.toGrant(): SignInGrant? = try {
    SignInGrant(token, Instant.parse(expiresAt), UserInfo(user.id, user.email, user.fullName.orEmpty()))
} catch (_: DateTimeParseException) {
    null
}
