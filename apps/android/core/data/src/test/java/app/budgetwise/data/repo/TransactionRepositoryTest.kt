package app.budgetwise.data.repo

import app.budgetwise.data.model.DailySpend
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.db.OutboxOp
import app.budgetwise.data.text.BurmeseTextNormalizer
import app.budgetwise.nlu.TxType
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransactionRepositoryTest {
    private lateinit var h: RepoHarness
    private val repo get() = h.transactions

    @Before fun setUp() { h = RepoHarness() }
    @After fun tearDown() { h.close() }

    private fun input(amount: Long, source: Source = Source.MANUAL) = NewTransaction(
        type = TxType.EXPENSE, amountKyat = amount, category = "Groceries", description = "rice",
        date = LocalDate.parse("2026-10-21"), source = source, transcript = if (source == Source.VOICE) "text" else null,
    )

    @Test
    fun addAllWritesRowsAndOutboxAtomically() = runBlocking {
        // Force the second outbox insert to fail: the first row and op must roll back with it.
        h.sync.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_outbox BEFORE INSERT ON outbox WHEN (SELECT COUNT(*) FROM outbox) >= 1 " +
                "BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )
        try {
            repo.addAll(listOf(input(100), input(200)))
            fail("expected the outbox insert to fail")
        } catch (_: Exception) {
        }
        assertEquals(0, repo.observe().first().size)
        assertEquals(0, h.sync.pendingOps().size)
        assertTrue("no sync is requested for a failed write", h.work("sync").isEmpty())
    }

    @Test
    fun aFixedClientIdSavedTwiceIsStoredOnce() = runBlocking {
        val kpay = "8757c8d9-1367-4ee0-b09b-80b464aba7a0"
        val first = repo.addAll(listOf(input(150000).copy(clientId = kpay)))
        val second = repo.addAll(listOf(input(150000).copy(clientId = kpay), input(2500)))
        assertEquals(listOf(kpay), first)
        assertEquals(kpay, second[0])
        assertEquals(2, repo.observe().first().size)
        assertEquals("one create per stored row", 2, h.sync.pendingOps().size)
    }

    @Test
    fun addAllNormalizesDescriptionAndTranscriptAndConvertsZawgyi() = runBlocking {
        val zawgyi = "ျမန္မာဘာသာ"
        val unicode = "မြန်မာဘာသာ"
        repo.addAll(
            listOf(
                input(100, Source.VOICE).copy(description = "  Grab \t ခ   ၅၀၀၀ ", transcript = "  $zawgyi  "),
                input(200).copy(description = zawgyi),
            ),
        )
        val rows = repo.observe().first().sortedBy { it.amountKyat }
        assertEquals("Grab ခ ၅၀၀၀", rows[0].description)
        assertEquals(unicode, rows[0].transcript)
        assertEquals(unicode, rows[1].description)
        assertEquals(null, rows[1].transcript)
    }

    @Test
    fun addAllRefusesADescriptionThatConvertsPastTheServerLimit() = runBlocking {
        // Short as typed (Zawgyi), longer once converted to Unicode: the stored text is what the server counts.
        val zawgyi = "ဒီေန႔ ေဈးမွာ ဆန္ဝယ္ခဲ့တယ္" + "ၻ".repeat(100)
        assertTrue(zawgyi.length <= TransactionRepository.MAX_DESCRIPTION)
        assertTrue(BurmeseTextNormalizer.prepare(zawgyi).length > TransactionRepository.MAX_DESCRIPTION)
        try {
            repo.addAll(listOf(input(100), input(200).copy(description = zawgyi)))
            fail("expected the converted description to be refused")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("nothing is stored, not even the valid row", 0, repo.observe().first().size)
        assertEquals(0, h.sync.pendingOps().size)
        assertTrue(h.work("sync").isEmpty())
    }

    @Test
    fun addAllGeneratesUniqueUuidV4ClientIds() = runBlocking {
        val ids = repo.addAll(List(20) { input(100L + it) })
        assertEquals(20, ids.toSet().size)
        ids.forEach { assertEquals(4, UUID.fromString(it).version()) }
    }

    @Test
    fun addAllStoresPendingRowsWithCreateOpsAndSchedulesOneSync() = runBlocking {
        val ids = repo.addAll(listOf(input(500, Source.VOICE), input(700)))
        val rows = repo.observe().first()
        assertEquals(setOf(500L, 700L), rows.map { it.amountKyat }.toSet())
        assertTrue(rows.all { it.syncState == SyncState.PENDING && it.serverId == null })
        assertEquals(Source.VOICE, rows.first { it.amountKyat == 500L }.source)
        assertEquals("text", rows.first { it.amountKyat == 500L }.transcript)
        val ops = h.sync.pendingOps()
        assertEquals(ids, ops.map { it.clientId })
        assertTrue(ops.all { it.op == OutboxOp.CREATE })
        assertEquals(1, h.work("sync").size)
        assertEquals(2, repo.observeUnsyncedCount().first())
    }

    @Test
    fun addAllWithNoInputsDoesNothing() = runBlocking {
        assertEquals(emptyList<String>(), repo.addAll(emptyList()))
        assertTrue(h.work("sync").isEmpty())
    }

    @Test
    fun deleteOfNeverSyncedRowDropsRowAndOpAndSchedulesSync() = runBlocking {
        val (id) = repo.addAll(listOf(input(100)))
        repo.delete(id)
        assertEquals(0, repo.observe().first().size)
        assertEquals(0, h.sync.pendingOps().size)
        assertEquals("the sync queued by addAll covers the delete", 1, h.work("sync").size)
    }

    @Test
    fun deleteOfSyncedRowQueuesDeleteOp() = runBlocking {
        val synced = h.sync.seedSynced("c-synced")
        repo.delete(synced.clientId)
        assertEquals(0, repo.observe().first().size)
        val op = h.sync.pendingOps().single()
        assertEquals(OutboxOp.DELETE, op.op)
        assertEquals(synced.serverId, op.serverId)
        assertEquals(1, h.work("sync").size)
    }

    @Test
    fun readsAndWritesBelongToTheActiveAccount() = runBlocking {
        val (mine) = repo.addAll(listOf(input(100)))
        assertEquals("u1", h.sync.tx.byClientId(mine)!!.ownerId)
        assertEquals("u1", h.sync.pendingOps("u1").single().ownerId)

        h.sync.signInSecond()
        assertTrue(repo.observe().first().isEmpty())
        assertEquals(0, repo.observeUnsyncedCount().first())
        val (theirs) = repo.addAll(listOf(input(200)))
        assertEquals("u2", h.sync.tx.byClientId(theirs)!!.ownerId)
        assertEquals(listOf(theirs), repo.observe().first().map { it.clientId })

        h.sync.session.signIn(h.sync.server.token, h.sync.now.plusSeconds(3600), app.budgetwise.data.sync.SyncHarness.USER)
        assertEquals(listOf(mine), repo.observe().first().map { it.clientId })
    }

    @Test
    fun dailySpendIsTheActiveAccountsOnly() = runBlocking {
        val october = LocalDate.parse("2026-10-01") to LocalDate.parse("2026-10-31")
        repo.addAll(listOf(input(100), input(250)))
        assertEquals(
            listOf(DailySpend(LocalDate.parse("2026-10-21"), "Groceries", 350)),
            repo.observeDailySpend(october.first, october.second).first(),
        )

        h.sync.signInSecond()
        assertTrue("another account's spending is not counted", repo.observeDailySpend(october.first, october.second).first().isEmpty())

        h.sync.session.signOut(keepSession = false)
        assertTrue(repo.observeDailySpend(october.first, october.second).first().isEmpty())
    }

    @Test
    fun addAllRefusesWhenNoAccountIsSignedIn() = runBlocking {
        h.sync.session.signOut(keepSession = false)
        try {
            repo.addAll(listOf(input(100)))
            fail("expected no owner")
        } catch (_: IllegalStateException) {
        }
        assertTrue(h.sync.tx.observeAll("u1").first().isEmpty())
    }
}
