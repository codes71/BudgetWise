package app.budgetwise.data.sync

import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.db.OutboxOp
import app.budgetwise.data.model.SyncState
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private lateinit var h: SyncHarness

    @Before
    fun setUp() {
        h = SyncHarness()
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun pushesInBatchesOfFifty() = runTest {
        val ids = h.addLocal(120)

        val outcome = h.engine.syncOnce()

        assertEquals(SyncOutcome.Success(pushed = 120, pulled = 0), outcome)
        assertEquals(listOf(50, 50, 20), h.server.posts.map { it.size })
        assertEquals(ids, h.server.posts.flatten())
        assertTrue(h.pendingOps().isEmpty())
        val local = ids.map { h.tx.byClientId(it)!! }
        assertTrue(local.all { it.syncState == SyncState.SYNCED })
        assertEquals(ids.map { h.server.rows.getValue(it).id }, local.map { it.serverId })
    }

    @Test
    fun retryAfterLostResponseDoesNotDuplicate() = runTest {
        val ids = h.addLocal(2)
        h.server.dropResponseOnPost = 1

        assertEquals(SyncOutcome.RetryLater, h.engine.syncOnce())
        assertEquals(2, h.server.rows.size) // stored on the server
        assertEquals(ids, h.pendingOps().map { it.clientId }) // but the device never heard back

        assertEquals(SyncOutcome.Success(pushed = 2, pulled = 0), h.engine.syncOnce())
        assertEquals(listOf(ids, ids), h.server.posts)
        assertEquals(2, h.server.rows.size)
        assertEquals(2, h.tx.observeAll("u1").first().size)
        assertTrue(h.pendingOps().isEmpty())
        ids.forEach { assertEquals(h.server.rows.getValue(it).id, h.tx.byClientId(it)!!.serverId) }
    }

    @Test
    fun unauthorizedKeepsOutboxAndFlagsReauth() = runTest {
        val ids = h.addLocal(120)
        h.server.unauthorizedOnPost = 2

        assertEquals(SyncOutcome.NeedsLogin, h.engine.syncOnce())

        assertEquals(2, h.server.posts.size) // stopped at batch 2, batch 3 never sent
        assertTrue(h.server.gets.isEmpty()) // and no pull
        assertEquals(ids.drop(50), h.pendingOps().map { it.clientId })
        assertTrue(ids.take(50).all { h.tx.byClientId(it)!!.syncState == SyncState.SYNCED })
        assertTrue(ids.drop(50).all { h.tx.byClientId(it)!!.syncState == SyncState.PENDING })
        assertEquals(SessionState.Expired, h.session.state.value)
        assertNull(h.session.bearer())
    }

    @Test
    fun needsLoginWithoutNetworkWhenNoBearer() = runTest {
        h.addLocal(1)
        h.session.signOut(keepSession = false)

        assertEquals(SyncOutcome.NeedsLogin, h.engine.syncOnce())

        assertEquals(0, h.web.requestCount)
        assertEquals(1, h.pendingOps().size)
    }

    @Test
    fun pullDoesNotOverwritePendingLocalRow() = runTest {
        h.seedSynced("c1")
        h.server.edit("c1") { it.copy(category = "Health") } // edited on the web
        // The user deletes the row while the pull is in flight: a pending DELETE, local intent.
        h.server.duringGet = { runBlocking { h.engine.enqueueDelete("c1") } }

        h.engine.syncOnce(fullPull = true)

        val local = h.tx.byClientId("c1")!!
        assertTrue(local.deleted)
        assertEquals("Other", local.category)
        assertEquals(listOf(OutboxOp.DELETE), h.pendingOps().map { it.op })
        assertTrue(h.tx.observeAll("u1").first().isEmpty())
    }

    @Test
    fun fullPullRemovesServerDeletedSyncedRowButKeepsPendingOnes() = runTest {
        h.seedSynced("gone")
        h.seedSynced("edited")
        h.seedSynced("doomed")
        h.server.rows.remove("gone") // deleted on the web
        h.server.rows.remove("doomed") // deleted on the web, and on the phone during the pull
        h.server.edit("edited") { it.copy(category = "Health") }
        var added = emptyList<String>()
        h.server.duringGet = {
            runBlocking {
                added = h.addLocal(1)
                h.engine.enqueueDelete("doomed")
            }
        }

        val outcome = h.engine.syncOnce(fullPull = true)

        assertEquals(listOf<String?>(null), h.server.gets) // full pull: no updatedSince
        assertEquals(SyncOutcome.Success(pushed = 0, pulled = 2), outcome)
        assertNull(h.tx.byClientId("gone"))
        assertEquals("Health", h.tx.byClientId("edited")!!.category)
        assertNotNull(h.tx.byClientId(added.single()))
        assertTrue(h.tx.byClientId("doomed")!!.deleted)
        assertEquals(setOf(added.single(), "doomed"), h.pendingOps().map { it.clientId }.toSet())

        // The next run pushes both: the new row reaches the server, the DELETE gets 404 and is done.
        h.server.duringGet = null
        assertEquals(SyncOutcome.Success(pushed = 2, pulled = 0), h.engine.syncOnce(fullPull = true))
        assertNull(h.tx.byClientId("doomed"))
        assertTrue(added.single() in h.server.rows)
        assertTrue(h.pendingOps().isEmpty())
    }

    @Test
    fun badServerRowIsSkippedAndTheRestArePulled() = runTest {
        h.seedSynced("bad")
        h.server.edit("bad") { it.copy(type = "transfer", amount = 7777.0, description = "secret lunch") }
        h.server.put("a")
        h.server.put("dec", amount = 12.5) // a decimal web entry is not bad: it rounds to whole kyat
        ShadowLog.clear()

        val outcome = h.engine.syncOnce(fullPull = true)

        assertEquals(SyncOutcome.Success(pushed = 0, pulled = 2), outcome)
        assertEquals(setOf("a", "bad", "dec"), h.tx.observeAll("u1").first().map { it.clientId }.toSet())
        assertEquals(1000L, h.tx.byClientId("bad")!!.amountKyat) // kept as it was, not removed by reconcile
        assertEquals(13L, h.tx.byClientId("dec")!!.amountKyat)
        val ours = ShadowLog.getLogsForTag(SYNC_LOG_TAG)
        assertTrue(ours.any { h.server.rows.getValue("bad").id in it.msg })
        assertTrue(ours.all { it.throwable == null })
        assertFalse(ShadowLog.getLogs().any { "7777" in it.msg || "secret" in it.msg })
    }

    @Test
    fun incrementalPullSendsCursorInUtcAndAdvancesWithTwoMinuteOverlap() = runTest {
        h.cursor.setLastPulledAt("u1", Instant.parse("2026-10-21T05:00:30.123456Z"))
        repeat(4) { h.server.put("r$it") } // updatedAt 05:01 .. 05:04

        assertEquals(SyncOutcome.Success(pushed = 0, pulled = 4), h.engine.syncOnce())

        assertEquals(listOf<String?>("2026-10-21T05:00:30.123Z"), h.server.gets)
        val newest = Instant.parse("2026-10-21T05:04:00Z")
        assertEquals(newest.minus(Duration.ofMinutes(2)), h.cursor.lastPulledAt("u1").first())
    }

    @Test
    fun deleteOfUnsyncedRowSendsNothing() = runTest {
        val id = h.addLocal(1).single()

        h.engine.enqueueDelete(id)

        assertNull(h.tx.byClientId(id))
        assertTrue(h.pendingOps().isEmpty())
        assertEquals(SyncOutcome.Success(pushed = 0, pulled = 0), h.engine.syncOnce())
        assertTrue(h.server.posts.isEmpty())
        assertTrue(h.server.deletes.isEmpty())
    }

    @Test
    fun deleteOfSyncedRowTombstonesThenDeletesOnServer() = runTest {
        h.seedSynced("c1")

        h.engine.enqueueDelete("c1")

        assertTrue(h.tx.observeAll("u1").first().isEmpty()) // hidden at once
        assertEquals(SyncOutcome.Success(pushed = 1, pulled = 0), h.engine.syncOnce())
        assertTrue(h.server.rows.isEmpty())
        assertNull(h.tx.byClientId("c1"))
        assertTrue(h.pendingOps().isEmpty())
    }

    @Test
    fun delete404IsSuccess() = runTest {
        val serverId = h.seedSynced("c1").serverId
        h.server.rows.remove("c1") // already deleted on the web
        h.engine.enqueueDelete("c1")

        assertEquals(SyncOutcome.Success(pushed = 1, pulled = 0), h.engine.syncOnce())

        assertEquals(listOf(serverId), h.server.deletes)
        assertNull(h.tx.byClientId("c1"))
        assertTrue(h.pendingOps().isEmpty())
    }

    @Test
    fun deleteAfterUnconfirmedCreateStillDeletesOnServer() = runTest {
        val id = h.addLocal(1).single()
        h.server.dropResponseOnPost = 1
        assertEquals(SyncOutcome.RetryLater, h.engine.syncOnce()) // the server has it, the phone does not know

        h.engine.enqueueDelete(id)
        assertTrue(h.tx.observeAll("u1").first().isEmpty())

        // The CREATE is resent to learn the server id, then the DELETE follows.
        assertEquals(SyncOutcome.Success(pushed = 2, pulled = 0), h.engine.syncOnce())
        assertTrue(h.server.rows.isEmpty())
        assertNull(h.tx.byClientId(id))
        assertTrue(h.pendingOps().isEmpty())
    }

    @Test
    fun permanent400MarksFailedAndContinues() = runTest {
        h.seedSynced("d1")
        val bad = h.addLocal(1).single()
        h.engine.enqueueDelete("d1") // queued behind the bad CREATE
        h.server.invalidClientIds = setOf(bad)
        h.server.invalidStatus = 400

        val outcome = h.engine.syncOnce()

        assertEquals(SyncOutcome.PermanentFailure(listOf(bad), pushed = 1, pulled = 0), outcome)
        assertEquals(SyncState.FAILED, h.tx.byClientId(bad)!!.syncState)
        assertTrue(h.pendingOps().isEmpty())
        assertTrue(h.server.rows.isEmpty()) // the DELETE behind it still went out
        assertEquals(1, h.server.gets.size) // and the pull ran

        // Never retried silently.
        assertEquals(SyncOutcome.Success(pushed = 0, pulled = 0), h.engine.syncOnce())
        assertEquals(1, h.server.posts.size)
    }

    @Test
    fun batch422PushesValidItemsAndMarksOnlyTheBadOneFailed() = runTest {
        val ids = h.addLocal(5)
        val bad = ids[2]
        h.server.invalidClientIds = setOf(bad)

        val outcome = h.engine.syncOnce()

        assertEquals(SyncOutcome.PermanentFailure(listOf(bad), pushed = 4, pulled = 0), outcome)
        assertEquals(listOf(ids) + ids.map { listOf(it) }, h.server.posts)
        assertEquals((ids - bad).toSet(), h.server.rows.keys)
        assertEquals(SyncState.FAILED, h.tx.byClientId(bad)!!.syncState)
        (ids - bad).forEach { assertEquals(SyncState.SYNCED, h.tx.byClientId(it)!!.syncState) }
        assertTrue(h.pendingOps().isEmpty())
    }

    @Test
    fun locallyInvalidRowFailsWithoutBeingSent() = runTest {
        val zero = h.addLocal(1, amount = 0).single()
        val ok = h.addLocal(1).single()

        assertEquals(SyncOutcome.PermanentFailure(listOf(zero), pushed = 1, pulled = 0), h.engine.syncOnce())

        assertEquals(listOf(listOf(ok)), h.server.posts)
        assertEquals(SyncState.FAILED, h.tx.byClientId(zero)!!.syncState)
    }

    @Test
    fun captivePortalPageDoesNotFailRows() = runTest {
        val ids = h.addLocal(3)
        h.server.htmlStatusOnPost = 200

        assertEquals(SyncOutcome.RetryLater, h.engine.syncOnce())

        assertEquals(1, h.server.posts.size) // no one-by-one quarantine for a response that is not about the rows
        assertEquals(ids, h.pendingOps().map { it.clientId })
        assertTrue(ids.all { h.tx.byClientId(it)!!.syncState == SyncState.PENDING })
    }

    @Test
    fun infrastructure4xxPageKeepsRowsPendingAndRetries() = runTest {
        val ids = h.addLocal(3)

        // A proxy, firewall or captive network answers with its own HTML page and no WS1 error envelope.
        for (status in listOf(403, 405, 413)) {
            h.server.htmlStatusOnPost = status
            assertEquals("HTTP $status", SyncOutcome.RetryLater, h.engine.syncOnce())
        }

        assertEquals(3, h.server.posts.size) // one batch per run, never quarantined item by item
        assertEquals(ids, h.pendingOps().map { it.clientId })
        assertTrue(ids.all { h.tx.byClientId(it)!!.syncState == SyncState.PENDING })

        h.server.htmlStatusOnPost = null
        assertEquals(SyncOutcome.Success(pushed = 3, pulled = 0), h.engine.syncOnce())
    }

    @Test
    fun deleteWhileCreateIsInFlightAndItsResponseIsLostStillDeletesOnServer() = runTest {
        val id = h.addLocal(1).single()
        h.server.dropResponseOnPost = 1
        h.server.duringPost = { runBlocking { h.engine.enqueueDelete(id) } } // the op is visibly in flight

        assertEquals(SyncOutcome.RetryLater, h.engine.syncOnce())
        assertTrue(h.tx.observeAll("u1").first().isEmpty()) // hidden at once
        assertEquals(listOf(OutboxOp.CREATE), h.pendingOps().map { it.op }) // kept to learn the server id

        h.server.duringPost = null
        assertEquals(SyncOutcome.Success(pushed = 2, pulled = 0), h.engine.syncOnce())
        assertTrue(h.server.rows.isEmpty()) // not resurrected by a later full pull
        assertNull(h.tx.byClientId(id))
        assertTrue(h.pendingOps().isEmpty())
    }

    @Test
    fun serverErrorOnPushStillPullsWebChanges() = runTest {
        val ids = h.addLocal(2)
        h.server.put("web")
        h.server.htmlStatusOnPost = 503

        assertEquals(SyncOutcome.RetryLater, h.engine.syncOnce(fullPull = true))

        assertEquals(listOf<String?>(null), h.server.gets) // the pull ran
        assertNotNull(h.tx.byClientId("web"))
        assertEquals(ids, h.pendingOps().map { it.clientId }) // the outbox waits for the retry
        assertTrue(ids.all { h.tx.byClientId(it)!!.syncState == SyncState.PENDING })
    }

    @Test
    fun unreachableServerOnPushSkipsThePull() = runTest {
        h.addLocal(1)
        h.server.dropResponseOnPost = 1 // the connection breaks: an IOException, as with no route to the server

        assertEquals(SyncOutcome.RetryLater, h.engine.syncOnce())

        assertTrue(h.server.gets.isEmpty())
        assertEquals(1, h.pendingOps().size)
    }

    @Test
    fun serverErrorOnPullKeepsDataAndRetries() = runTest {
        h.seedSynced("c1")
        h.server.getStatus = 503

        assertEquals(SyncOutcome.RetryLater, h.engine.syncOnce(fullPull = true))

        assertNotNull(h.tx.byClientId("c1"))
        assertNull(h.cursor.lastPulledAt("u1").first())
    }

    @Test
    fun statusIsRunningDuringSyncThenRecordsLastResult() = runTest {
        var during: SyncStatus? = null
        h.server.duringGet = { during = h.engine.status.value }
        assertEquals(SyncStatus.Idle, h.engine.status.value)

        val outcome = h.engine.syncOnce()

        assertEquals(SyncStatus.Running, during)
        assertEquals(SyncStatus.LastResult(h.now, outcome), h.engine.status.value)
        assertSame(h.engine.status.value, h.engine.lastResult.value)
    }

    @Test
    fun aStoppedRunPutsTheOldStatusBackButRecordsNoResult() = runBlocking {
        val first = h.engine.syncOnce()
        val recorded = h.engine.lastResult.value
        val entered = CompletableDeferred<Unit>()
        h.server.duringGet = {
            entered.complete(Unit)
            Thread.sleep(500) // hold the pull while the run is cancelled
        }

        val run = launch(Dispatchers.IO) { h.engine.syncOnce() }
        entered.await()
        run.cancelAndJoin()

        assertEquals(SyncStatus.LastResult(h.now, first), h.engine.status.value)
        assertSame(recorded, h.engine.lastResult.value) // no new instance: nothing finished
    }

    @Test
    fun aPendingDeleteSurvivesThePullAfterAFailedPush() = runTest {
        h.seedSynced("x")
        val created = h.addLocal(1) // a CREATE heads the outbox
        h.engine.enqueueDelete("x") // then the tombstone's DELETE
        h.server.htmlStatusOnPost = 503

        assertEquals(SyncOutcome.RetryLater, h.engine.syncOnce(fullPull = true))

        assertEquals(listOf<String?>(null), h.server.gets) // the full pull ran and still listed x
        assertTrue(h.tx.byClientId("x")!!.deleted)
        assertEquals(OutboxOp.DELETE, h.pendingOps().single { it.clientId == "x" }.op)
        assertEquals(created, h.tx.observeAll("u1").first().map { it.clientId }) // x stays hidden
    }
}
