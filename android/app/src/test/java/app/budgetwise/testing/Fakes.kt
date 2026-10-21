package app.budgetwise.testing

import app.budgetwise.AccountActions
import app.budgetwise.SyncControls
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.auth.TokenStore
import app.budgetwise.data.auth.UserInfo
import app.budgetwise.data.repo.LoginResult
import app.budgetwise.data.repo.LogoutResult
import app.budgetwise.data.sync.SyncStatus
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

val TestUser = UserInfo(id = "u1", email = "aye@example.com", fullName = "Aye Aye")

/** In-memory [TokenStore]. [gate], when set, holds [load] until completed. */
class FakeTokenStore(var session: StoredSession? = null) : TokenStore {
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun save(token: String, expiresAt: Instant, user: UserInfo) {
        session = StoredSession(token, expiresAt, user)
    }

    override suspend fun load(): StoredSession? {
        gate?.await()
        return session
    }

    override suspend fun clear() {
        session = null
    }
}

data class LoginCall(val email: String, val password: String, val discard: Boolean)

class FakeAccountActions : AccountActions {
    val logins = mutableListOf<LoginCall>()
    val logouts = mutableListOf<Boolean>()
    var onLogin: (LoginCall) -> LoginResult = { error("no login result set") }
    var onLogout: (force: Boolean) -> LogoutResult = { LogoutResult.SignedOut }

    /** When set, login suspends here, so a test can look at the loading state. */
    var loginGate: CompletableDeferred<Unit>? = null

    override suspend fun login(email: String, password: String, discardOtherUsersData: Boolean): LoginResult {
        val call = LoginCall(email, password, discardOtherUsersData)
        logins += call
        loginGate?.await()
        return onLogin(call)
    }

    override suspend fun logout(force: Boolean): LogoutResult {
        logouts += force
        return onLogout(force)
    }
}

class FakeSyncControls : SyncControls {
    override val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val queued = MutableStateFlow(false)
    override val unsyncedCount = MutableStateFlow(0)
    var syncNowCalls = 0

    override fun syncNow() {
        syncNowCalls++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}
