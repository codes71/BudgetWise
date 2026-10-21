package app.budgetwise.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.auth.TokenStore
import app.budgetwise.data.auth.UserInfo
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.db.OutboxEntity
import app.budgetwise.data.db.OutboxOp
import app.budgetwise.data.db.TransactionEntity
import app.budgetwise.data.db.toEntity
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.net.NetLogging
import app.budgetwise.data.net.createApiService
import app.budgetwise.data.net.createOkHttpClient
import app.budgetwise.data.net.toDomain
import app.budgetwise.nlu.TxType
import java.io.Closeable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer

/**
 * Real Room (in memory), real Retrofit and OkHttp against [FakeServer], real [SessionManager]. Only the
 * token store and the pull cursor are in-memory fakes.
 */
class SyncHarness : Closeable {
    val now: Instant = Instant.parse("2026-10-21T06:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    val server = FakeServer()
    val web = MockWebServer().apply {
        dispatcher = server
        start()
    }
    val db: BudgetWiseDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BudgetWiseDb::class.java)
        .allowMainThreadQueries()
        .build()
    val tx = db.transactionDao()
    val outbox = db.outboxDao()
    val cursor = MemoryCursor()
    val session = SessionManager(MemoryTokenStore(), clock)
    val engine: SyncEngine

    init {
        runBlocking { session.signIn(server.token, now.plus(Duration.ofDays(30)), UserInfo("u1", "a@b.co", "Aye Aye")) }
        // The engine's own idempotency is under test, so OkHttp must not silently resend a dropped POST.
        val client = createOkHttpClient({ session.bearer() }, NetLogging.None).newBuilder()
            .retryOnConnectionFailure(false)
            .build()
        // An IPv4 literal: "localhost" also resolves to ::1, where MockWebServer does not listen, and after one failure
        // OkHttp tries that route next.
        engine = SyncEngine(db, createApiService("http://127.0.0.1:${web.port}/api/v1/", client), session, cursor, clock)
    }

    /** What TransactionRepository.addAll does: a PENDING row plus its CREATE op, in one transaction. */
    suspend fun addLocal(n: Int, amount: Long = 1000): List<String> = List(n) {
        val id = UUID.randomUUID().toString()
        db.withTransaction {
            tx.upsert(
                TransactionEntity(
                    clientId = id, serverId = null, type = TxType.EXPENSE, amountKyat = amount, category = "Other",
                    description = "tea", date = LocalDate.parse("2026-10-21"), source = Source.MANUAL,
                    transcript = null, updatedAt = now, syncState = SyncState.PENDING,
                ),
            )
            outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = id))
        }
        id
    }

    /** A row that is on the server and already synced to the device. */
    suspend fun seedSynced(clientId: String): TransactionEntity {
        val entity = server.put(clientId).toDomain().toEntity()
        tx.upsert(entity)
        return entity
    }

    suspend fun pendingOps(): List<OutboxEntity> = outbox.head(Int.MAX_VALUE)

    override fun close() {
        db.close()
        web.shutdown()
    }
}

class MemoryCursor : PullCursorStore {
    private val value = MutableStateFlow<Instant?>(null)
    override val lastPulledAt: StateFlow<Instant?> = value
    override suspend fun setLastPulledAt(at: Instant) {
        value.value = at
    }
}

private class MemoryTokenStore : TokenStore {
    private var session: StoredSession? = null
    override suspend fun save(token: String, expiresAt: Instant, user: UserInfo) {
        session = StoredSession(token, expiresAt, user)
    }
    override suspend fun load(): StoredSession? = session
    override suspend fun clear() {
        session = null
    }
}
