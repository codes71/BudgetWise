package app.budgetwise.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.data.db.BudgetEntity
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.db.OutboxDao
import app.budgetwise.data.db.OutboxEntity
import app.budgetwise.data.db.OutboxOp
import app.budgetwise.data.db.TransactionDao
import app.budgetwise.data.db.TransactionEntity
import app.budgetwise.data.db.claimUnowned
import app.budgetwise.data.db.toDomain
import app.budgetwise.data.db.toEntity
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.DailySpend
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.nlu.TxType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var db: BudgetWiseDb
    private lateinit var tx: TransactionDao
    private lateinit var outbox: OutboxDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BudgetWiseDb::class.java)
            .allowMainThreadQueries()
            .build()
        tx = db.transactionDao()
        outbox = db.outboxDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(
        id: String,
        date: String = "2026-10-19",
        updatedAt: String = "2026-10-19T03:00:00Z",
        amount: Long = 1000,
        state: SyncState = SyncState.PENDING,
        deleted: Boolean = false,
        owner: String? = "u1",
    ) = TransactionEntity(
        clientId = id, ownerId = owner, serverId = null, type = TxType.EXPENSE, amountKyat = amount, category = "Other",
        description = "d", date = LocalDate.parse(date), source = Source.MANUAL, transcript = null,
        updatedAt = Instant.parse(updatedAt), syncState = state, deleted = deleted,
    )

    @Test
    fun observeAll_ordersNewestFirst() = runTest {
        tx.upsert(row("old", date = "2026-10-17"))
        tx.upsert(row("same-day-early", date = "2026-10-19", updatedAt = "2026-10-19T01:00:00Z"))
        tx.upsert(row("same-day-late", date = "2026-10-19", updatedAt = "2026-10-19T05:00:00Z"))
        tx.upsert(row("newest", date = "2026-10-20"))
        val ids = tx.observeAll("u1").first().map { it.clientId }
        assertEquals(listOf("newest", "same-day-late", "same-day-early", "old"), ids)
    }

    @Test
    fun enqueue_preservesFifoBySeq() = runTest {
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "a", ownerId = "u1"))
        outbox.enqueue(OutboxEntity(op = OutboxOp.DELETE, clientId = "b", serverId = "srv-b", ownerId = "u1"))
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "c", ownerId = "u1"))
        val head = outbox.head("u1", 2)
        assertEquals(listOf("a", "b"), head.map { it.clientId })
        assertTrue(head[0].seq < head[1].seq)
        assertEquals("srv-b", head[1].serverId)
        assertEquals(OutboxOp.DELETE, head[1].op)
    }

    @Test
    fun ackAndBump_affectOnlyGivenSeqs() = runTest {
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "a", ownerId = "u1"))
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "b", ownerId = "u1"))
        val (a, b) = outbox.head("u1", 10)
        outbox.bump(listOf(a.seq))
        assertEquals(listOf(1, 0), outbox.head("u1", 10).map { it.attempts })
        outbox.ack(listOf(a.seq))
        assertEquals(listOf(b.seq), outbox.head("u1", 10).map { it.seq })
    }

    @Test
    fun upsertSameClientIdReplaces() = runTest {
        tx.upsert(row("x", amount = 1000))
        tx.upsert(row("x", amount = 2500, state = SyncState.SYNCED))
        val all = tx.observeAll("u1").first()
        assertEquals(1, all.size)
        assertEquals(2500L, all[0].amountKyat)
        assertEquals(SyncState.SYNCED, tx.byClientId("x")?.syncState)
    }

    @Test
    fun tombstonedRowsHiddenFromObserve() = runTest {
        tx.upsert(row("live"))
        tx.upsert(row("gone", deleted = true))
        assertEquals(listOf("live"), tx.observeAll("u1").first().map { it.clientId })
        assertTrue(tx.byClientId("gone")?.deleted == true)
    }

    @Test
    fun hasPendingFor_trueOnlyWhileOpQueued() = runTest {
        assertFalse(outbox.hasPendingFor("a"))
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "a", ownerId = "u1"))
        assertTrue(outbox.hasPendingFor("a"))
        assertFalse(outbox.hasPendingFor("b"))
        outbox.ack(outbox.head("u1", 10).map { it.seq })
        assertFalse(outbox.hasPendingFor("a"))
    }

    @Test
    fun syncedClientIds_and_deleteByClientId() = runTest {
        tx.upsert(row("s1", state = SyncState.SYNCED))
        tx.upsert(row("s2", state = SyncState.SYNCED))
        tx.upsert(row("p", state = SyncState.PENDING))
        assertEquals(setOf("s1", "s2"), tx.syncedClientIds("u1").toSet())
        tx.deleteByClientId("s1")
        assertNull(tx.byClientId("s1"))
        assertEquals(listOf("s2"), tx.syncedClientIds("u1"))
    }

    @Test
    fun entityDomainMappingRoundTrips() = runTest {
        val e = row("m", amount = 4200).copy(serverId = "srv", transcript = "t", type = TxType.INCOME, source = Source.VOICE)
        tx.upsert(e)
        val back = tx.byClientId("m")!!
        assertEquals(e, back)
        assertEquals(e, back.toDomain().toEntity(e.ownerId))
    }

    @Test
    fun queriesSeeOnlyTheGivenAccount() = runTest {
        tx.upsert(row("mine", state = SyncState.SYNCED))
        tx.upsert(row("theirs", state = SyncState.SYNCED, owner = "u2"))
        tx.upsert(row("theirs-pending", owner = "u2"))
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "theirs-pending", ownerId = "u2"))

        assertEquals(listOf("mine"), tx.observeAll("u1").first().map { it.clientId })
        assertEquals(listOf("mine"), tx.syncedClientIds("u1"))
        assertEquals(0, tx.observeUnsyncedCount("u1").first())
        assertEquals(1, tx.observeUnsyncedCount("u2").first())
        assertTrue(outbox.head("u1", 10).isEmpty())
        assertEquals(listOf("theirs-pending"), outbox.head("u2", 10).map { it.clientId })
        assertEquals(listOf("u2"), outbox.owners())
        assertEquals(1, outbox.countFor("u2"))
    }

    @Test
    fun claimUnownedGivesOldRowsOpsAndBudgetsToOneAccountOnly() = runTest {
        tx.upsert(row("old", owner = null))
        tx.upsert(row("theirs", owner = "u2"))
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "old", ownerId = null))
        val budgets = db.budgetDao()
        budgets.insertAll(listOf(BudgetEntity(ownerId = null, category = "Other", limitKyat = 100)))
        budgets.insertAll(listOf(BudgetEntity(ownerId = null, category = "Health", limitKyat = 200)))
        budgets.insertAll(listOf(BudgetEntity(ownerId = "u1", category = "Health", limitKyat = 300)))
        assertTrue("unowned rows are hidden", tx.observeAll("u1").first().isEmpty())

        db.claimUnowned("u1")

        assertEquals("u1", tx.byClientId("old")!!.ownerId)
        assertEquals("u2", tx.byClientId("theirs")!!.ownerId)
        assertEquals(listOf("old"), outbox.head("u1", 10).map { it.clientId })
        assertEquals(listOf(Budget("Health", 300), Budget("Other", 100)), budgets.observeAll("u1").first().map { it.toDomain() })

        db.claimUnowned("u2")
        assertEquals("a second claim finds nothing left", "u1", tx.byClientId("old")!!.ownerId)
    }

    @Test
    fun observeDailySpend_sumsTheOwnersExpensesPerDayAndCategory() = runTest {
        tx.upsert(row("a", date = "2026-10-01", amount = 1_000))
        tx.upsert(row("b", date = "2026-10-01", amount = 2_500))
        tx.upsert(row("c", date = "2026-10-01", amount = 400).copy(category = "Transport"))
        tx.upsert(row("d", date = "2026-09-15", amount = 7_000))
        // Left out: income, a tombstone, another account, and days outside the range.
        tx.upsert(row("income", date = "2026-10-01", amount = 850_000).copy(type = TxType.INCOME, category = "Income"))
        tx.upsert(row("gone", date = "2026-10-01", amount = 9_999, deleted = true))
        tx.upsert(row("theirs", date = "2026-10-01", amount = 5_000, owner = "u2"))
        tx.upsert(row("early", date = "2026-08-31", amount = 3_000))
        tx.upsert(row("late", date = "2026-11-01", amount = 3_000))

        val spend = tx.observeDailySpend("u1", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-31")).first()

        assertEquals(
            setOf(
                DailySpend(LocalDate.parse("2026-10-01"), "Other", 3_500),
                DailySpend(LocalDate.parse("2026-10-01"), "Transport", 400),
                DailySpend(LocalDate.parse("2026-09-15"), "Other", 7_000),
            ),
            spend.toSet(),
        )
    }
}
