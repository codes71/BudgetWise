package app.budgetwise.data.repo

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.db.BudgetEntity
import app.budgetwise.data.db.MonthPlanEntity
import app.budgetwise.data.net.NetLogging
import app.budgetwise.data.net.createApiService
import app.budgetwise.data.net.createOkHttpClient
import app.budgetwise.data.sync.SyncHarness
import app.budgetwise.data.sync.SyncEngine
import app.budgetwise.data.sync.MemoryCursor
import java.time.Duration
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountDeletionRepositoryTest {
    private lateinit var h: RepoHarness
    private lateinit var deletion: AccountDeletionRepository
    private val user = SyncHarness.USER
    private val other = SyncHarness.USER2

    @Before fun setUp() {
        h = RepoHarness()
        val client = createOkHttpClient(NetLogging.None).newBuilder().retryOnConnectionFailure(false)
            .readTimeout(2, TimeUnit.SECONDS).connectTimeout(2, TimeUnit.SECONDS).build()
        deletion = AccountDeletionRepository(
            createApiService("http://127.0.0.1:${h.apiServer.port}/api/v1/", client),
            h.sync.session, h.sync.db, h.sync.engine, h.writes, h.dataStore,
        )
    }
    @After fun close() { h.close() }

    private suspend fun seed() {
        h.sync.addLocal(1, owner = user.id)
        h.sync.addLocal(1, owner = other.id)
        h.sync.db.budgetDao().insertAll(listOf(
            BudgetEntity(ownerId = user.id, category = "Other", limitKyat = 100),
            BudgetEntity(ownerId = other.id, category = "Other", limitKyat = 200),
        ))
        h.sync.db.monthPlanDao().insertAll(listOf(user, other).map {
            MonthPlanEntity(it.id, YearMonth.of(2026, 10), 100, 0, "[]", null, null)
        })
        h.sync.signInSecond()
        h.sync.session.signIn(h.sync.server.token, h.sync.now.plus(Duration.ofDays(30)), user)
        h.dataStore.edit {
            it[stringPreferencesKey("categories_json.${user.id}")] = "[\"Private\"]"
            it[stringPreferencesKey("categories_json.${other.id}")] = "[\"Other\"]"
        }
    }

    private suspend fun assertOwnDataPresent() {
        assertEquals(1, h.sync.tx.observeAll(user.id).first().size)
        assertEquals(1, h.sync.outbox.countFor(user.id))
        assertEquals(user.id, h.sync.session.activeUserId.value)
        assertNotNull(h.sync.session.bearerFor(user.id))
    }

    @Test fun confirmedDeletionRemovesOnlyItsOwnerAndCredentials() = runBlocking {
        seed()
        h.apiServer.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AccountDeletionResult.Success, deletion.delete(user.id, "current-password"))
        val request = h.apiServer.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/account", request.path)
        assertEquals("Bearer ${h.sync.server.token}", request.getHeader("Authorization"))
        assertEquals("{\"password\":\"current-password\"}", request.body.readUtf8())
        assertTrue(h.sync.tx.observeAll(user.id).first().isEmpty())
        assertEquals(0, h.sync.outbox.countFor(user.id))
        assertTrue(h.sync.db.budgetDao().observeAll(user.id).first().isEmpty())
        assertNull(h.sync.db.monthPlanDao().observe(user.id, YearMonth.of(2026, 10)).first())
        assertNull(h.sync.session.accountFor(user.email))
        assertEquals(SessionState.SignedOut, h.sync.session.state.value)
        assertEquals(1, h.sync.tx.observeAll(other.id).first().size)
        assertEquals(1, h.sync.outbox.countFor(other.id))
        assertNotNull(h.sync.session.accountFor(other.email))
        assertNotNull(h.sync.session.bearerFor(other.id))
        assertNull(h.dataStore.data.first()[stringPreferencesKey("categories_json.${user.id}")])
        assertNotNull(h.dataStore.data.first()[stringPreferencesKey("categories_json.${other.id}")])
    }

    @Test fun wrongPasswordAndIncompleteDeletionPreserveAllLocalData() = runBlocking {
        seed()
        h.json(401, """{"error":{"code":"invalid_password","message":"Invalid password"}}""")
        assertEquals(AccountDeletionResult.WrongPassword, deletion.delete(user.id, "wrong"))
        assertOwnDataPresent()
        h.json(503, """{"error":{"code":"deletion_incomplete","message":"Retry"}}""")
        assertEquals(AccountDeletionResult.DeletionIncomplete, deletion.delete(user.id, "current"))
        assertOwnDataPresent()
        assertEquals(user.id, h.sync.session.writableUserId)
    }

    @Test fun networkFailureDoesNotTreatTheAccountAsDeleted() = runBlocking {
        seed()
        h.apiServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(AccountDeletionResult.NeedsInternet, deletion.delete(user.id, "current"))
        assertOwnDataPresent()
        assertFalse(h.sync.session.wasDeleted(user.id))
    }

    @Test fun aSuccessfulStatusOtherThan204DoesNotDeleteAnything() = runBlocking {
        seed()
        h.json(200, "{}")
        assertEquals(AccountDeletionResult.TryAgain, deletion.delete(user.id, "current"))
        assertOwnDataPresent()
    }

    @Test fun aChangedActiveAccountNeverSendsDeletionForTheNewAccount() = runBlocking {
        seed()
        h.sync.signInSecond()
        assertEquals(AccountDeletionResult.AccountChanged, deletion.delete(user.id, "current"))
        assertEquals(0, h.apiServer.requestCount)
        assertEquals(other.id, h.sync.session.activeUserId.value)
        assertEquals(1, h.sync.tx.observeAll(user.id).first().size)
    }

    @Test fun anExpiredSessionCanReauthenticateWithoutSwitchingItsIdentity() = runBlocking {
        seed()
        h.sync.session.signIn("expired", h.sync.now.minusSeconds(1), user)
        h.json(200, """{"token":"reauth-token","expiresAt":"2026-12-01T00:00:00Z","user":{"id":"u1","email":"a@b.co","fullName":"Aye"}}""")
        h.apiServer.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AccountDeletionResult.Success, deletion.delete(user.id, "current"))
        assertEquals("/api/v1/auth/token", h.apiServer.takeRequest().path)
        assertEquals("Bearer reauth-token", h.apiServer.takeRequest().getHeader("Authorization"))
        assertNotNull(h.sync.session.accountFor(other.email))
    }

    @Test fun reauthenticationForAnotherIdentityNeverSendsDelete() = runBlocking {
        seed()
        h.sync.session.signIn("expired", h.sync.now.minusSeconds(1), user)
        h.json(200, """{"token":"reauth-token","expiresAt":"2026-12-01T00:00:00Z","user":{"id":"u2","email":"b@c.co","fullName":"Other"}}""")
        assertEquals(AccountDeletionResult.AccountChanged, deletion.delete(user.id, "current"))
        assertEquals(1, h.apiServer.requestCount)
        assertEquals(user.id, h.sync.session.activeUserId.value)
        assertEquals(1, h.sync.tx.observeAll(user.id).first().size)
    }

    @Test fun aConcurrentAccountSwitchWaitsForThePinnedDeletion() = runBlocking {
        seed()
        h.apiServer.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(300, TimeUnit.MILLISECONDS))
        val removed = async(Dispatchers.IO) { deletion.delete(user.id, "current") }
        assertNotNull(h.apiServer.takeRequest(2, TimeUnit.SECONDS))
        val switched = async(Dispatchers.IO) { h.sync.signInSecond() }
        delay(50)
        assertFalse(switched.isCompleted)
        assertEquals(AccountDeletionResult.Success, removed.await())
        switched.await()
        assertEquals(other.id, h.sync.session.activeUserId.value)
        assertEquals(1, h.sync.tx.observeAll(other.id).first().size)
    }

    @Test fun lateCacheRepliesAndStaleLoginCannotRestoreDeletedOwner() = runBlocking {
        seed()
        val generation = h.writes.generation(user.id, CacheWrites.Table.BUDGETS)
        h.apiServer.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AccountDeletionResult.Success, deletion.delete(user.id, "current"))
        var writes = 0
        h.writes.writeSaved(user.id, listOf(CacheWrites.Table.BUDGETS)) { writes++ }
        assertFalse(h.writes.writeIfUnchanged(user.id, CacheWrites.Table.BUDGETS, generation) { writes++ })
        assertEquals(0, writes)
        try {
            h.sync.session.signIn("stale", h.sync.now.plusSeconds(60), user)
            fail("A stale sign-in must not restore a deleted account")
        } catch (_: IllegalStateException) { }
        assertEquals(SessionState.SignedOut, h.sync.session.state.value)
    }

    @Test fun confirmedCleanupFailureRecoversLocallyWithoutCallingTheDeletedServer() = runBlocking {
        seed()
        h.apiServer.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AccountDeletionResult.LocalCleanupFailed, deletion.delete(user.id, "current") { error("platform failure") })
        assertTrue(h.dataStore.data.first()[stringSetPreferencesKey("confirmed_account_deletions")]!!.contains(user.id))
        deletion.recoverPendingDeletions()
        assertEquals(1, h.apiServer.requestCount)
        assertNull(h.sync.session.accountFor(user.email))
        assertNotNull(h.sync.session.accountFor(other.email))
        assertNull(h.dataStore.data.first()[stringSetPreferencesKey("confirmed_account_deletions")])
    }

    @Test fun aLostDeleteReplyRemainsRetryableAfterSync401AndProcessReload() = runBlocking {
        seed()
        // The request reached the server, but the client never received its confirmation.
        h.apiServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(AccountDeletionResult.NeedsInternet, deletion.delete(user.id, "current"))
        h.sync.session.onUnauthorized(user.id, h.sync.server.token)
        assertNull(h.sync.session.bearerFor(user.id))
        assertNotNull(h.sync.tokens.saved.byId(user.id)!!.deletionAccess)
        val reloaded = SessionManager(h.sync.tokens, h.sync.clock)
        reloaded.load()
        val recovered = AccountDeletionRepository(
            h.api, reloaded, h.sync.db, SyncEngine(h.sync.db, h.api, reloaded, MemoryCursor(), h.sync.clock), h.writes, h.dataStore,
        )
        h.apiServer.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AccountDeletionResult.Success, recovered.delete(user.id, "current"))
        assertEquals("DELETE", h.apiServer.takeRequest().method)
        assertEquals("DELETE", h.apiServer.takeRequest().method)
        assertEquals(2, h.apiServer.requestCount)
        assertNull(reloaded.accountFor(user.email))
        assertNotNull(reloaded.accountFor(other.email))
    }

    @Test fun startupCleanupFailureNeverExposesTheConfirmedDeletedAccount() = runBlocking {
        seed()
        // This receipt is the only authority for local-only cleanup, and is normally written after observed 204.
        h.dataStore.edit { it[stringSetPreferencesKey("confirmed_account_deletions")] = setOf(user.id) }
        val reloaded = SessionManager(h.sync.tokens, h.sync.clock)
        val recovered = AccountDeletionRepository(
            h.api, reloaded, h.sync.db, SyncEngine(h.sync.db, h.api, reloaded, MemoryCursor(), h.sync.clock), h.writes, h.dataStore,
        )
        recovered.quarantinePendingDeletions()
        reloaded.load()
        assertEquals(SessionState.SignedOut, reloaded.state.value)
        assertNull(reloaded.knownUser)
        assertNull(reloaded.activeUserId.value)
        assertNull(reloaded.accountFor(user.email))
        assertNull(reloaded.bearerFor(user.id))
        h.sync.db.close() // Simulate a local cleanup failure after session loading, before Room rows can be removed.
        try { recovered.recoverPendingDeletions(); fail("Closed database must fail cleanup") } catch (_: IllegalStateException) { }
        assertEquals(SessionState.SignedOut, reloaded.state.value)
        assertTrue(h.dataStore.data.first()[stringSetPreferencesKey("confirmed_account_deletions")]!!.contains(user.id))
        try { reloaded.signInOffline(user.id, "pw"); fail("A quarantined account must not sign in offline") } catch (_: IllegalStateException) { }
        assertNotNull(reloaded.accountFor(other.email))
        reloaded.signInOffline(other.id, "pw")
        assertEquals(other.id, reloaded.activeUserId.value)
        // Offline sign-in preserves this other account's still-valid online access token.
        assertTrue(reloaded.state.value is SessionState.SignedIn)
        assertEquals(0, h.apiServer.requestCount)
    }
}
