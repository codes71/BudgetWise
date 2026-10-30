package app.budgetwise.data.repo

import androidx.room.withTransaction
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.db.OutboxEntity
import app.budgetwise.data.db.OutboxOp
import app.budgetwise.data.db.TransactionEntity
import app.budgetwise.data.db.toDomain
import app.budgetwise.data.model.DailySpend
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.sync.SyncEngine
import app.budgetwise.data.sync.SyncScheduler
import app.budgetwise.data.text.BurmeseTextNormalizer
import app.budgetwise.nlu.TxType
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class NewTransaction(
    val type: TxType,
    val amountKyat: Long,
    val category: String,
    val description: String,
    val date: LocalDate,
    val source: Source,
    val transcript: String?,
    /** A fixed id, so saving the same thing twice stores it once (a KPay transaction); null gets a new one. */
    val clientId: String? = null,
)

/**
 * The UI reads Room through this class and never the network. Writes go to Room and the outbox first. Reads show the
 * active account's rows only, and follow it when it changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TransactionRepository @Inject constructor(
    private val db: BudgetWiseDb,
    private val session: SessionManager,
    private val syncEngine: SyncEngine,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) {
    private val tx = db.transactionDao()
    private val outbox = db.outboxDao()

    fun observe(): Flow<List<Transaction>> = session.activeUserId.flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else tx.observeAll(owner).map { rows -> rows.map { it.toDomain() } }
    }

    /** The active account's spending from [from] to [to] (both included), per day and category. */
    fun observeDailySpend(from: LocalDate, to: LocalDate): Flow<List<DailySpend>> = session.activeUserId.flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else tx.observeDailySpend(owner, from, to)
    }

    /** The active account's rows not yet confirmed by the server (queued, rejected, or awaiting delete). */
    fun observeUnsyncedCount(): Flow<Int> = session.activeUserId.flatMapLatest { owner ->
        if (owner == null) flowOf(0) else tx.observeUnsyncedCount(owner)
    }

    /**
     * Saves every input as a PENDING row of the active account with a fresh UUIDv4 clientId (or its fixed
     * [NewTransaction.clientId]) plus its CREATE op, all in one Room transaction (a failure leaves no rows). A sync is
     * requested only after the commit. Returns the clientIds of the rows it stored, in input order: an input whose fixed
     * id this account already holds is the same entry saved again, and is skipped. Description and transcript go through [BurmeseTextNormalizer.prepare] (Zawgyi to Unicode, NFC,
     * whitespace) before they are stored. Throws [IllegalStateException] when no account is signed in.
     */
    suspend fun addAll(inputs: List<NewTransaction>): List<String> {
        if (inputs.isEmpty()) return emptyList()
        val owner = checkNotNull(session.activeUserId.value) { "no account is signed in" }
        val now = clock.instant()
        // Converting Zawgyi to Unicode can lengthen the text, so the server's limit is checked on what is stored.
        val descriptions = inputs.map { BurmeseTextNormalizer.prepare(it.description) }
        require(descriptions.all { it.length <= MAX_DESCRIPTION }) {
            "description is longer than $MAX_DESCRIPTION characters once prepared"
        }
        val ids = db.withTransaction {
            inputs.mapIndexedNotNull { index, input ->
                // A fixed id this account already has is the same entry saved again: the one there stays, untouched.
                // Another account's (two people scanning one KPay slip on a shared phone), or a row deleted here and
                // waiting for the server, keeps its id: this entry gets a new one instead.
                val existing = input.clientId?.let { tx.byClientId(it) }
                if (existing != null && existing.ownerId == owner && !existing.deleted) return@mapIndexedNotNull null
                val clientId = input.clientId?.takeIf { existing == null } ?: UUID.randomUUID().toString()
                tx.upsert(
                    TransactionEntity(
                        clientId = clientId,
                        ownerId = owner,
                        serverId = null,
                        type = input.type,
                        amountKyat = input.amountKyat,
                        category = input.category,
                        description = descriptions[index],
                        date = input.date,
                        source = input.source,
                        transcript = input.transcript?.let(BurmeseTextNormalizer::prepare),
                        updatedAt = now,
                        syncState = SyncState.PENDING,
                    ),
                )
                outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = clientId, ownerId = owner))
                clientId
            }
        }
        if (ids.isNotEmpty()) syncScheduler.requestAfterWrite()
        return ids
    }

    /** Deletes locally at once ([SyncEngine.enqueueDelete] handles never-synced and synced rows), then syncs. */
    suspend fun delete(clientId: String) {
        syncEngine.enqueueDelete(clientId)
        syncScheduler.requestAfterWrite()
    }

    companion object {
        /** The server's limit for a description, in characters, measured on the stored (prepared) text. */
        const val MAX_DESCRIPTION = 200
    }
}
