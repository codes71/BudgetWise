package app.budgetwise.data.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreTokenStoreTest {
    private lateinit var store: KeystoreTokenStore
    private val user = UserInfo("u1", "a@b.co", "Aye Aye")
    private val expiresAt = Instant.parse("2026-11-20T00:00:00Z")

    @Before
    fun setUp() = runTest {
        store = KeystoreTokenStore(ApplicationProvider.getApplicationContext())
        store.clear()
    }

    @After
    fun tearDown() = runTest { store.clear() }

    @Test
    fun saveLoadRoundTrip() = runTest {
        store.save("fake-token-123", expiresAt, user)
        assertEquals(StoredSession("fake-token-123", expiresAt, user), store.load())
    }

    @Test
    fun newInstanceReadsSavedSession() = runTest {
        store.save("fake-token-123", expiresAt, user)
        val other = KeystoreTokenStore(ApplicationProvider.getApplicationContext())
        assertEquals("fake-token-123", other.load()?.token)
    }

    @Test
    fun clearRemovesSession() = runTest {
        store.save("fake-token-123", expiresAt, user)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun emptyStoreLoadsNull() = runTest { assertNull(store.load()) }
}
