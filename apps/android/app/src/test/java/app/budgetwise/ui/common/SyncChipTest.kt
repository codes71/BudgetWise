package app.budgetwise.ui.common

import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.sync.SyncOutcome
import app.budgetwise.data.sync.SyncStatus
import app.budgetwise.testing.TestUser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncChipTest {
    private val signedIn = SessionState.SignedIn(TestUser, Instant.parse("2026-11-20T00:00:00Z"), expiringSoon = false)
    private val at = Instant.parse("2026-10-21T03:00:00Z")

    private fun chip(
        session: SessionState = signedIn,
        online: Boolean = true,
        status: SyncStatus = SyncStatus.LastResult(at, SyncOutcome.Success(0, 0)),
        queued: Boolean = false,
        unsynced: Int = 0,
    ) = syncChipFor(session, online, status, queued, unsynced)

    @Test
    fun aFinishedSyncSaysSynced() = assertEquals(SyncChip.SYNCED, chip())

    @Test
    fun aRunningOrQueuedSyncSaysSyncing() {
        assertEquals(SyncChip.SYNCING, chip(status = SyncStatus.Running))
        assertEquals("queued after a save", SyncChip.SYNCING, chip(queued = true))
    }

    @Test
    fun noInternetSaysOffline() {
        assertEquals(SyncChip.OFFLINE, chip(online = false))
        assertEquals("a sync cannot run offline", SyncChip.OFFLINE, chip(online = false, status = SyncStatus.Running))
    }

    @Test
    fun aServerOutOfReachSaysNotSynced() {
        assertEquals(SyncChip.NOT_SYNCED, chip(status = SyncStatus.LastResult(at, SyncOutcome.RetryLater)))
        // Backing off after the failure still says so, not "syncing".
        assertEquals(SyncChip.NOT_SYNCED, chip(status = SyncStatus.LastResult(at, SyncOutcome.RetryLater), queued = true))
    }

    @Test
    fun anEndedSessionSaysSignInWhateverElseIsTrue() {
        assertEquals(SyncChip.SIGN_IN, chip(session = SessionState.Expired, online = false, status = SyncStatus.Running))
        assertEquals(SyncChip.SIGN_IN, chip(status = SyncStatus.LastResult(at, SyncOutcome.NeedsLogin)))
    }

    @Test
    fun refusedEntriesSayCouldNotSyncEvenOffline() {
        val refused = SyncStatus.LastResult(at, SyncOutcome.PermanentFailure(listOf("a", "b"), pushed = 0, pulled = 0))
        assertEquals(SyncChip.COULD_NOT_SYNC, chip(status = refused))
        assertEquals(SyncChip.COULD_NOT_SYNC, chip(status = refused, online = false))
    }

    @Test
    fun beforeTheFirstSyncItIsSyncedOnlyWhenNothingWaits() {
        assertEquals(SyncChip.SYNCED, chip(status = SyncStatus.Idle))
        assertEquals(SyncChip.NOT_SYNCED, chip(status = SyncStatus.Idle, unsynced = 2))
        assertEquals(SyncChip.SYNCING, chip(status = SyncStatus.Idle, queued = true, unsynced = 2))
    }

    @Test
    fun signedOutHasNoChip() = assertNull(chip(session = SessionState.SignedOut))
}
