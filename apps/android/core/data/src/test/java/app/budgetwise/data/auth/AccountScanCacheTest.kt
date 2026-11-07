package app.budgetwise.data.auth

import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountScanCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val receipt = byteArrayOf(1, 2, 3, 4)
    private val otherReceipt = byteArrayOf(5, 6)
    private fun root() = temporary.newFolder("scan")
    private fun File.files() = walkTopDown().filter { it.isFile }.toList()

    private suspend fun rejected(lease: AccountScanCache.Lease) {
        try {
            lease.keep(receipt)
            fail("a stale scan must not recreate its receipt")
        } catch (_: IOException) { }
    }

    @Test fun sameOwnerProcessRestartRestoresOnlyTheCompletePhotoAndDropsThePartial() = runBlocking {
        val root = root()
        val cache = AccountScanCache(root)
        cache.activate("owner")
        val lease = cache.begin()
        lease.keep(receipt)
        val partial = File(root.files().single().parentFile, "current.jpg.part").apply { writeBytes(otherReceipt) }

        val restarted = AccountScanCache(root)
        restarted.activate("owner")
        val restored = restarted.begin(lease.id)
        assertArrayEquals(receipt, restored.kept())
        assertFalse(partial.exists())
        restored.drop()
        assertTrue(root.files().isEmpty())
    }

    @Test fun oldScreenCleanupAndLateWritesCannotEraseOrReplaceANewerScanForTheSameOwner() = runBlocking {
        val root = root()
        val cache = AccountScanCache(root)
        cache.activate("owner")
        val old = cache.begin()
        old.keep(receipt)
        val next = cache.begin()
        next.keep(otherReceipt)
        old.drop()
        rejected(old)
        assertNull(old.kept())
        assertArrayEquals(otherReceipt, next.kept())
        assertEquals(1, root.files().size)
    }

    @Test fun aRecoveredScanIsNotDeletedByItsOldScreen() = runBlocking {
        val cache = AccountScanCache(root())
        cache.activate("owner")
        val old = cache.begin()
        old.keep(receipt)
        val restored = cache.begin(old.id)
        old.drop()
        assertArrayEquals(receipt, restored.kept())
        rejected(old)
    }

    @Test fun accountSwitchInvalidatesQueuedOldWorkEvenAfterSwitchingBack() = runBlocking {
        val root = root()
        val cache = AccountScanCache(root)
        cache.activate("owner")
        val old = cache.begin()
        old.keep(receipt)
        val resumeOldWork = CompletableDeferred<Unit>()
        val lateWrite = async { resumeOldWork.await(); rejected(old); old.drop() }

        cache.activate("other")
        assertTrue("switch is awaited", root.files().isEmpty())
        val other = cache.begin()
        other.keep(otherReceipt)
        resumeOldWork.complete(Unit)
        lateWrite.await()
        assertArrayEquals(otherReceipt, other.kept())
        cache.activate("owner")
        rejected(old)
        assertNull(cache.begin(old.id).kept())
    }

    @Test fun confirmedDeletionCleansPartialAndLegacyFilesAndCannotBeUndoneByALateWrite() = runBlocking {
        val root = root()
        val cache = AccountScanCache(root)
        cache.activate("owner")
        val lease = cache.begin()
        lease.keep(receipt)
        File(root.files().single().parentFile, "current.jpg.part").writeBytes(otherReceipt)
        File(root, "current.jpg").writeBytes(receipt)
        File(root, "current.jpg.part").writeBytes(otherReceipt)
        cache.removeOwner("owner")
        assertTrue(root.files().isEmpty())
        rejected(lease)
        lease.drop()
        assertTrue(root.files().isEmpty())
    }

    @Test fun inactiveOwnerDeletionRecoveryKeepsTheActiveOwnersCurrentScan() = runBlocking {
        val root = root()
        val cache = AccountScanCache(root)
        cache.activate("other")
        val active = cache.begin()
        active.keep(otherReceipt)
        cache.removeOwner("owner")
        assertArrayEquals(otherReceipt, active.kept())
        assertTrue(active.active)
        assertFalse(cache.beginForOwner("owner").active)
        assertArrayEquals(otherReceipt, active.kept())
    }

    @Test fun startupCannotAssignALegacyUnownedReceiptToTheSignedInOwner() = runBlocking {
        val root = root()
        File(root, "current.jpg").writeBytes(receipt)
        File(root, "current.jpg.part").writeBytes(otherReceipt)
        val cache = AccountScanCache(root)
        cache.activate("owner")
        assertTrue(root.files().isEmpty())
        assertNull(cache.begin().kept())
    }

    @Test fun sessionTransitionsAwaitCleanupAndSameOwnerStartupKeepsRecovery() = runBlocking {
        val root = root()
        val cache = AccountScanCache(root)
        val now = Instant.parse("2026-11-07T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val owner = UserInfo("owner", "owner@example.test", "Owner")
        val other = UserInfo("other", "other@example.test", "Other")
        val tokens = object : TokenStore {
            var saved = SavedAccounts.EMPTY
            override suspend fun load() = saved
            override suspend fun save(accounts: SavedAccounts) { saved = accounts }
        }
        val session = SessionManager(tokens, clock, cache)
        session.signIn("test", now.plusSeconds(3600), owner)
        val lease = cache.begin()
        lease.keep(receipt)
        val restartedCache = AccountScanCache(root)
        val restartedSession = SessionManager(tokens, clock, restartedCache)
        restartedSession.load()
        assertArrayEquals(receipt, restartedCache.begin(lease.id).kept())
        restartedSession.signIn("other-test", now.plusSeconds(3600), other)
        assertEquals("other", restartedSession.activeUserId.value)
        assertTrue(root.files().isEmpty())
        val otherScan = restartedCache.begin()
        otherScan.keep(otherReceipt)
        restartedSession.signOut(keepSession = true)
        assertNull(restartedSession.activeUserId.value)
        assertTrue(root.files().isEmpty())
        rejected(otherScan)
    }

    @Test fun aSessionStillBeingPersistedCannotLendTheNextOwnersCacheToThePreviousScreen() = runBlocking {
        val cache = AccountScanCache(root())
        val now = Instant.parse("2026-11-07T00:00:00Z")
        val owner = UserInfo("owner", "owner@example.test", "Owner")
        val other = UserInfo("other", "other@example.test", "Other")
        val startedSaving = CompletableDeferred<Unit>()
        val finishSaving = CompletableDeferred<Unit>()
        val tokens = object : TokenStore {
            override suspend fun load() = SavedAccounts.EMPTY
            override suspend fun save(accounts: SavedAccounts) {
                if (accounts.activeId == other.id) {
                    startedSaving.complete(Unit)
                    finishSaving.await()
                    throw IOException("simulated token storage failure")
                }
            }
        }
        val session = SessionManager(tokens, Clock.fixed(now, ZoneOffset.UTC), cache)
        session.signIn("test", now.plusSeconds(3600), owner)
        val transition = async {
            try {
                session.signIn("other-test", now.plusSeconds(3600), other)
                fail("the token store fails")
            } catch (_: IOException) { }
        }
        startedSaving.await()
        assertEquals(owner.id, session.activeUserId.value)
        assertFalse(cache.beginForOwner(session.writableUserId).active)
        finishSaving.complete(Unit)
        transition.await()
        assertEquals(owner.id, session.activeUserId.value)
        val stillOwner = cache.beginForOwner(session.writableUserId)
        stillOwner.keep(receipt)
        assertArrayEquals(receipt, stillOwner.kept())
    }
}
