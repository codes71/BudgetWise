package app.budgetwise.data.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreTokenStoreTest {
    private lateinit var store: KeystoreTokenStore
    private val user = UserInfo("u1", "a@b.co", "Aye Aye")
    private val other = UserInfo("u2", "b@c.co", "Bo Bo")
    private val expiresAt = Instant.parse("2026-11-20T00:00:00Z")
    private val check = PasswordCheck(ByteArray(16) { 1 }, ByteArray(32) { 2 }, 120_000)

    private val accounts = SavedAccounts(
        activeId = "u2",
        accounts = listOf(
            SavedAccount(user, AccessToken("fake-token-123", expiresAt), check, signedOut = true),
            SavedAccount(other, null, null),
        ),
    )

    @Before
    fun setUp() = runTest {
        store = KeystoreTokenStore(ApplicationProvider.getApplicationContext())
        store.clear()
    }

    @After
    fun tearDown() = runTest { store.clear() }

    @Test
    fun saveLoadRoundTrip() = runTest {
        store.save(accounts)
        assertEquals(accounts, store.load())
    }

    @Test
    fun newInstanceReadsSavedAccounts() = runTest {
        store.save(accounts)
        val other = KeystoreTokenStore(ApplicationProvider.getApplicationContext())
        assertEquals("fake-token-123", other.load().byId("u1")?.access?.token)
    }

    @Test
    fun clearRemovesEverything() = runTest {
        store.save(accounts)
        store.clear()
        assertEquals(SavedAccounts.EMPTY, store.load())
    }

    @Test
    fun emptyStoreLoadsEmpty() = runTest { assertEquals(SavedAccounts.EMPTY, store.load()) }

    @Test
    fun recoversAfterKeyIsDeleted() = runTest {
        store.save(accounts)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(SESSION_KEY_ALIAS)
        assertEquals(SavedAccounts.EMPTY, store.load())
        store.save(SavedAccounts.of(StoredSession("fake-token-456", expiresAt, user)))
        assertEquals("fake-token-456", store.load().active?.access?.token)
    }
}
