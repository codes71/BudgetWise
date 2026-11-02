package app.budgetwise.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.data.auth.SavedAccounts
import app.budgetwise.data.auth.SessionManager
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer

/**
 * Real Room (in memory), real Retrofit and OkHttp against [FakeBackend], real [SessionManager]. Only the token store
 * and the pull cursor are in-memory fakes. [USER] is signed in with [server]'s token; [server2] is a second account
 * ([USER2]) on the same backend, signed in only when a test asks ([signInSecond]).
 */
class SyncHarness : Closeable {
    val now: Instant = Instant.parse("2026-10-21T06:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    val server = FakeServer()
    val server2 = FakeServer(token = "test-token-2-not-a-secret", idPrefix = "srv2")
    val backend = FakeBackend(listOf(server, server2))
    val web = MockWebServer().apply {
        dispatcher = backend
        start()
    }
    val db: BudgetWiseDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BudgetWiseDb::class.java)
        .allowMainThreadQueries()
        .build()
    val tx = db.transactionDao()
    val outbox = db.outboxDao()
    val cursor = MemoryCursor()
    val tokens = MemoryTokenStore()
    val session = SessionManager(tokens, clock)
    val engine: SyncEngine

    init {
        runBlocking { session.signIn(server.token, now.plus(Duration.ofDays(30)), USER) }
        // The engine's own idempotency is under test, so OkHttp must not silently resend a dropped POST.
        val client = createOkHttpClient(NetLogging.None).newBuilder()
            .retryOnConnectionFailure(false)
            .build()
        // An IPv4 literal: "localhost" also resolves to ::1, where MockWebServer does not listen, and after one failure
        // OkHttp tries that route next.
        engine = SyncEngine(db, createApiService("http://127.0.0.1:${web.port}/api/v1/", client), session, cursor, clock)
    }

    /** What TransactionRepository.addAll does: a PENDING row of [owner] plus its CREATE op, in one transaction. */
    suspend fun addLocal(n: Int, amount: Long = 1000, owner: String? = USER.id): List<String> = List(n) {
        val id = UUID.randomUUID().toString()
        db.withTransaction {
            tx.upsert(
                TransactionEntity(
                    clientId = id, ownerId = owner, serverId = null, type = TxType.EXPENSE, amountKyat = amount,
                    category = "Other", description = "tea", date = LocalDate.parse("2026-10-21"), source = Source.MANUAL,
                    transcript = null, updatedAt = now, syncState = SyncState.PENDING,
                ),
            )
            outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = id, ownerId = owner))
        }
        id
    }

    /** A row that is on [on]'s server and already synced to the device as [owner]'s. */
    suspend fun seedSynced(clientId: String, owner: String = USER.id, on: FakeServer = server): TransactionEntity {
        val entity = on.put(clientId).toDomain().toEntity(owner)
        tx.upsert(entity)
        return entity
    }

    suspend fun pendingOps(owner: String = USER.id): List<OutboxEntity> = outbox.head(owner, Int.MAX_VALUE)

    /** [USER2] signs in online and becomes the active account; [USER] stays on the phone with its own session. */
    suspend fun signInSecond() = session.signIn(server2.token, now.plus(Duration.ofDays(30)), USER2)

    override fun close() {
        db.close()
        web.shutdown()
    }

    companion object {
        val USER = UserInfo("u1", "a@b.co", "Aye Aye")
        val USER2 = UserInfo("u2", "b@c.co", "Bo Bo")
    }
}

class MemoryCursor : PullCursorStore {
    private val values = HashMap<String, MutableStateFlow<Instant?>>()

    private fun of(owner: String) = synchronized(values) { values.getOrPut(owner) { MutableStateFlow(null) } }

    override fun lastPulledAt(owner: String): Flow<Instant?> = of(owner)

    override suspend fun setLastPulledAt(owner: String, at: Instant) {
        of(owner).value = at
    }
}

class MemoryTokenStore : TokenStore {
    var saved: SavedAccounts = SavedAccounts.EMPTY

    override suspend fun load(): SavedAccounts = saved

    override suspend fun save(accounts: SavedAccounts) {
        saved = accounts
    }
}
