package app.budgetwise.ui.scan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.data.auth.AccountScanCache
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SavedAccounts
import app.budgetwise.data.auth.TokenStore
import app.budgetwise.data.auth.UserInfo
import java.time.Clock
import java.time.Instant
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanPhotosTest {
    private suspend fun session(cache: AccountScanCache): SessionManager {
        val store = object : TokenStore {
            override suspend fun load() = SavedAccounts.EMPTY
            override suspend fun save(accounts: SavedAccounts) = Unit
        }
        return SessionManager(store, Clock.systemUTC(), cache).also {
            it.signIn("test", Instant.now().plusSeconds(3600), UserInfo("owner", "owner@example.test", "Owner"))
        }
    }

    @Test
    fun abandoningAScanRemovesAnInterruptedWriteToo() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val cache = AccountScanCache(context).also { it.activate("owner") }
            val photos = DefaultScanPhotos(context, scope, cache, session(cache)).begin()
            photos.keep(byteArrayOf(1, 2, 3))
            val final = File(context.cacheDir, "scan").walkTopDown().single { it.name == "current.jpg" }
            val partial = File(final.parentFile, "current.jpg.part").apply { writeBytes(byteArrayOf(4, 5)) }
            photos.drop()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() }
            assertFalse(final.exists())
            assertFalse("a killed write still contains the receipt", partial.exists())
        } finally {
            scope.cancel()
            File(context.cacheDir, "scan").deleteRecursively()
        }
    }

    @Test
    fun queuedRetakeCleanupCannotEraseTheNextPhotoOrAdoptAnotherOwner() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Queue cleanup deliberately, so the replacement write completes before the old delete is resumed.
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val cache = AccountScanCache(context).also { it.activate("owner") }
            val photos = DefaultScanPhotos(context, scope, cache, session(cache)).begin()
            val first = photos.id
            photos.keep(byteArrayOf(1))
            photos.drop()
            assertTrue(first != photos.id)
            photos.keep(byteArrayOf(2, 3))
            dispatcher.scheduler.advanceUntilIdle()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() }
            val final = File(context.cacheDir, "scan").walkTopDown().single { it.name == "current.jpg" }
            assertArrayEquals(byteArrayOf(2, 3), final.readBytes())

            cache.activate("other")
            photos.drop()
            assertFalse(photos.active)
            assertTrue("old screen remains bound to its original owner", photos.owner == "owner")
        } finally {
            scope.cancel()
            File(context.cacheDir, "scan").deleteRecursively()
        }
    }
}
