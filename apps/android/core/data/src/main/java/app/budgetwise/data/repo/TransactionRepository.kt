package app.budgetwise.data.repo

import androidx.room.withTransaction
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.db.OutboxEntity
import app.budgetwise.data.db.OutboxOp
import app.budgetwise.data.db.TransactionEntity
import app.budgetwise.data.db.toDomain
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class NewTransaction(
    val type: TxType,
    val amountKyat: Long,
    val category: String,
    val description: String,
    val date: LocalDate,
    val source: Source,
    val transcript: String?,
)

/** The UI reads Room through this class and never the network. Writes go to Room and the outbox first. */
@Singleton
class TransactionRepository @Inject constructor(
    private val db: BudgetWiseDb,
    private val syncEngine: SyncEngine,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) {
    private val tx = db.transactionDao()
    private val outbox = db.outboxDao()

    fun observe(): Flow<List<Transaction>> = tx.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** Rows not yet confirmed by the server (queued, rejected, or awaiting delete). */
    fun observeUnsyncedCount(): Flow<Int> = tx.observeUnsyncedCount()

    /**
     * Saves every input as a PENDING row with a fresh UUIDv4 clientId plus its CREATE op, all in one Room
     * transaction (a failure leaves no rows). A sync is requested only after the commit. Returns the clientIds
     * in input order. Description and transcript go through [BurmeseTextNormalizer.prepare] (Zawgyi to Unicode, NFC,
     * whitespace) before they are stored.
     */
    suspend fun addAll(inputs: List<NewTransaction>): List<String> {
        if (inputs.isEmpty()) return emptyList()
        val now = clock.instant()
        // Converting Zawgyi to Unicode can lengthen the text, so the server's limit is checked on what is stored.
        val descriptions = inputs.map { BurmeseTextNormalizer.prepare(it.description) }
        require(descriptions.all { it.length <= MAX_DESCRIPTION }) {
            "description is longer than $MAX_DESCRIPTION characters once prepared"
        }
        val ids = db.withTransaction {
            inputs.mapIndexed { index, input ->
                val clientId = UUID.randomUUID().toString()
                tx.upsert(
                    TransactionEntity(
                        clientId = clientId,
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
                outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = clientId))
                clientId
            }
        }
        syncScheduler.requestAfterWrite()
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
