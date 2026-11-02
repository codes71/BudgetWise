package app.budgetwise.data.repo

import androidx.room.withTransaction
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.db.ItemsJson
import app.budgetwise.data.db.OutboxEntity
import app.budgetwise.data.db.OutboxOp
import app.budgetwise.data.db.TransactionEntity
import app.budgetwise.data.db.toDomain
import app.budgetwise.data.model.DailySpend
import app.budgetwise.data.model.EntryItem
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
    /** Shared by the entries saved from one scanned receipt. */
    val receiptId: String? = null,
    val merchant: String? = null,
    val items: List<EntryItem> = emptyList(),
)

/** What [TransactionRepository.replace] did. */
sealed interface ReplaceResult {
    /** The old entries are deleted and the inputs saved in their place: the clientIds it stored. */
    data class Saved(val clientIds: List<String>) : ReplaceResult

    /**
     * An entry to change was gone already (deleted here, or on the web and then pulled away, or another account's):
     * nothing was written, so a change never brings back on its own an entry deleted elsewhere.
     */
    data object Gone : ReplaceResult
}

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

    /** The active account's entries from one scanned receipt; empty once they are all deleted. */
    fun observeReceipt(receiptId: String): Flow<List<Transaction>> = session.activeUserId.flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else tx.observeReceipt(owner, receiptId).map { rows -> rows.map { it.toDomain() } }
    }

    /** One of the active account's entries, or null when it is gone (deleted, or another account's). */
    suspend fun get(clientId: String): Transaction? {
        val owner = session.activeUserId.value ?: return null
        return tx.byClientId(clientId)?.takeIf { it.ownerId == owner && !it.deleted }?.toDomain()
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
        val ids = db.withTransaction { store(inputs) }
        if (ids.isNotEmpty()) syncScheduler.requestAfterWrite()
        return ids
    }

    /**
     * An edit: deletes [old] (as [delete] does) and saves [inputs] in their place, in one Room transaction, then syncs.
     * Entries are never changed on the server, so a changed entry is a new one. Its CREATE is queued before the DELETEs,
     * as one edit ([OutboxEntity.replaceGroup]): the server deletes an original only after it took the change, and the
     * original stays when it refuses the change ([SyncEngine]). An input with the fixed id of the entry it replaces (a
     * KPay payment, so scanning it again still finds it saved) is that entry rewritten in place instead: deleted on the
     * server, then created again under the same id.
     *
     * With [inputs] it is a change, and [ReplaceResult.Gone] when any of [old] is gone already; without, it deletes what
     * is left of [old]. [ReplaceResult.Saved] has the stored clientIds as [addAll] gives them, the new ones first.
     */
    suspend fun replace(old: List<String>, inputs: List<NewTransaction>): ReplaceResult {
        val result = db.withTransaction {
            if (inputs.isEmpty()) {
                old.forEach { syncEngine.enqueueDelete(it) }
                return@withTransaction ReplaceResult.Saved(emptyList())
            }
            val owner = checkNotNull(session.activeUserId.value) { "no account is signed in" }
            // As get() sees it: a tombstone, or another account's row after a switch while the form was open, is gone.
            val rows = old.associateWith { tx.byClientId(it) }
            if (rows.values.any { it == null || it.deleted || it.ownerId != owner }) return@withTransaction ReplaceResult.Gone
            // Not while a CREATE of it went out without a reply: the server may hold it as it was, and the resent CREATE
            // would bring that back over the change. That one gets a new id like any other change.
            val inPlace = inputs.mapNotNullTo(HashSet()) { input ->
                input.clientId?.takeIf { id -> id in rows && !(rows.getValue(id)?.serverId == null && outbox.wasSent(id)) }
            }
            val fresh = inputs.filter { it.clientId !in inPlace }.map { if (it.clientId in rows) it.copy(clientId = null) else it }
            val group = joinedGroup(old) ?: UUID.randomUUID().toString()
            val ids = store(fresh, group)
            old.forEach { syncEngine.enqueueDelete(it, group.takeIf { _ -> it !in inPlace }) }
            val kept = store(inputs.filter { it.clientId in inPlace }, group, reuse = inPlace)
            requeueDeletes(group)
            ReplaceResult.Saved(ids + kept)
        }
        if (result is ReplaceResult.Saved) syncScheduler.requestAfterWrite()
        return result
    }

    /**
     * The edit a change joins: an entry changed again before its first change was sent is part of that edit, and the
     * DELETEs waiting on it must now wait on this change. Several become one. Null when there is none. Read before the
     * old entries' ops are dropped.
     */
    private suspend fun joinedGroup(old: List<String>): String? {
        val groups = old.flatMap { outbox.groupsOf(it) }.distinct()
        val group = groups.firstOrNull() ?: return null
        if (groups.size > 1) outbox.mergeGroups(groups.drop(1), group)
        return group
    }

    /** Queues [group]'s DELETEs again, behind every CREATE of it, so none goes out before the change it waits on. */
    private suspend fun requeueDeletes(group: String) {
        val deletes = outbox.deletesIn(group)
        if (deletes.isEmpty()) return
        outbox.ack(deletes.map { it.seq })
        deletes.forEach { outbox.enqueue(it.copy(seq = 0)) }
    }

    /**
     * [addAll]'s rows and CREATE ops, part of the edit [group] when there is one; the caller holds the Room transaction.
     * [reuse] are tombstones of entries rewritten in place: an input with one of those ids takes it over.
     */
    private suspend fun store(inputs: List<NewTransaction>, group: String? = null, reuse: Set<String> = emptySet()): List<String> {
        if (inputs.isEmpty()) return emptyList()
        val owner = checkNotNull(session.activeUserId.value) { "no account is signed in" }
        val now = clock.instant()
        // Converting Zawgyi to Unicode can lengthen the text, so the server's limit is checked on what is stored.
        val descriptions = inputs.map { BurmeseTextNormalizer.prepare(it.description) }
        require(descriptions.all { it.length <= MAX_DESCRIPTION }) {
            "description is longer than $MAX_DESCRIPTION characters once prepared"
        }
        return inputs.mapIndexedNotNull { index, input ->
            // A fixed id this account already has is the same entry saved again: the one there stays, untouched.
            // Another account's (two people scanning one KPay slip on a shared phone), or a row deleted here and
            // waiting for the server, keeps its id: this entry gets a new one instead.
            val existing = input.clientId?.let { tx.byClientId(it) }
            if (existing != null && existing.ownerId == owner && !existing.deleted) return@mapIndexedNotNull null
            val replacing = existing != null && existing.clientId in reuse && existing.ownerId == owner
            val clientId = input.clientId?.takeIf { existing == null || replacing } ?: UUID.randomUUID().toString()
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
                    receiptId = input.receiptId,
                    merchant = input.merchant?.let(BurmeseTextNormalizer::prepare)?.take(MAX_MERCHANT)?.ifEmpty { null },
                    itemsJson = ItemsJson.encode(
                        input.items.take(MAX_ITEMS).map { it.copy(name = BurmeseTextNormalizer.prepare(it.name).take(MAX_ITEM_NAME)) },
                    ),
                ),
            )
            outbox.enqueue(OutboxEntity(op = OutboxOp.CREATE, clientId = clientId, ownerId = owner, replaceGroup = group))
            clientId
        }
    }

    /** Deletes locally at once ([SyncEngine.enqueueDelete] handles never-synced and synced rows), then syncs. */
    suspend fun delete(clientId: String) {
        syncEngine.enqueueDelete(clientId)
        syncScheduler.requestAfterWrite()
    }

    companion object {
        /** The server's limit for a description, in characters, measured on the stored (prepared) text. */
        const val MAX_DESCRIPTION = 200

        /** The server's limit for a category name, in characters. The web takes longer ones, which the phone cannot send. */
        const val MAX_CATEGORY = 60

        /** The server's limits for a receipt's shop name, a line's name, and an entry's lines. */
        const val MAX_MERCHANT = 100
        const val MAX_ITEM_NAME = 120
        const val MAX_ITEMS = 100
    }
}
