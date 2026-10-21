package app.budgetwise.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.db.OutboxDao
import app.budgetwise.data.db.OutboxEntity
import app.budgetwise.data.db.OutboxOp
import app.budgetwise.data.db.TransactionDao
import app.budgetwise.data.db.TransactionEntity
import app.budgetwise.data.db.toDomain
import app.budgetwise.data.db.toEntity
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
    ) = TransactionEntity(
        clientId = id, serverId = null, type = TxType.EXPENSE, amountKyat = amount, category = "Other",
        description = "d", date = LocalDate.parse(date), source = Source.MANUAL, transcript = null,
        updatedAt = Instant.parse(updatedAt), syncState = state, deleted = deleted,
    )

    @Test
    fun observeAll_ordersNewestFirst() = runTest {
        tx.upsert(row("old", date = "2026-10-17"))
        tx.upsert(row("same-day-early", date = "2026-10-19", updatedAt = "2026-10-19T01:00:00Z"))
        tx.upsert(row("same-day-late", date = "2026-10-19", updatedAt = "2026-10-19T05:00:00Z"))
        tx.upsert(row("newest", date = "2026-10-20"))
        val ids = tx.observeAll().first().map { it.clientId }
        assertEquals(listOf("newest", "same-day-late", "same-day-early", "old"), ids)
    }

    @Test
    fun enqueue_preservesFifoBySeq() = runTest {
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "a"))
        outbox.enqueue(OutboxEntity(op = OutboxOp.DELETE, clientId = "b", serverId = "srv-b"))
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "c"))
        val head = outbox.head(2)
        assertEquals(listOf("a", "b"), head.map { it.clientId })
        assertTrue(head[0].seq < head[1].seq)
        assertEquals("srv-b", head[1].serverId)
        assertEquals(OutboxOp.DELETE, head[1].op)
    }

    @Test
    fun ackAndBump_affectOnlyGivenSeqs() = runTest {
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "a"))
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "b"))
        val (a, b) = outbox.head(10)
        outbox.bump(listOf(a.seq))
        assertEquals(listOf(1, 0), outbox.head(10).map { it.attempts })
        outbox.ack(listOf(a.seq))
        assertEquals(listOf(b.seq), outbox.head(10).map { it.seq })
    }

    @Test
    fun upsertSameClientIdReplaces() = runTest {
        tx.upsert(row("x", amount = 1000))
        tx.upsert(row("x", amount = 2500, state = SyncState.SYNCED))
        val all = tx.observeAll().first()
        assertEquals(1, all.size)
        assertEquals(2500L, all[0].amountKyat)
        assertEquals(SyncState.SYNCED, tx.byClientId("x")?.syncState)
    }

    @Test
    fun tombstonedRowsHiddenFromObserve() = runTest {
        tx.upsert(row("live"))
        tx.upsert(row("gone", deleted = true))
        assertEquals(listOf("live"), tx.observeAll().first().map { it.clientId })
        assertTrue(tx.byClientId("gone")?.deleted == true)
    }

    @Test
    fun hasPendingFor_trueOnlyWhileOpQueued() = runTest {
        assertFalse(outbox.hasPendingFor("a"))
        outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = "a"))
        assertTrue(outbox.hasPendingFor("a"))
        assertFalse(outbox.hasPendingFor("b"))
        outbox.ack(outbox.head(10).map { it.seq })
        assertFalse(outbox.hasPendingFor("a"))
    }

    @Test
    fun syncedClientIds_and_deleteByClientId() = runTest {
        tx.upsert(row("s1", state = SyncState.SYNCED))
        tx.upsert(row("s2", state = SyncState.SYNCED))
        tx.upsert(row("p", state = SyncState.PENDING))
        assertEquals(setOf("s1", "s2"), tx.syncedClientIds().toSet())
        tx.deleteByClientId("s1")
        assertNull(tx.byClientId("s1"))
        assertEquals(listOf("s2"), tx.syncedClientIds())
    }

    @Test
    fun entityDomainMappingRoundTrips() = runTest {
        val e = row("m", amount = 4200).copy(serverId = "srv", transcript = "t", type = TxType.INCOME, source = Source.VOICE)
        tx.upsert(e)
        val back = tx.byClientId("m")!!
        assertEquals(e, back)
        assertEquals(e, back.toDomain().toEntity())
    }
}
