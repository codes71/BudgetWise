package app.budgetwise.data.sync

import android.util.Log
import androidx.room.withTransaction
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.db.OutboxEntity
import app.budgetwise.data.db.OutboxOp
import app.budgetwise.data.db.TransactionEntity
import app.budgetwise.data.db.toDomain
import app.budgetwise.data.db.toEntity
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.InvalidWireDataException
import app.budgetwise.data.net.SavedResponse
import app.budgetwise.data.net.TransactionDto
import app.budgetwise.data.net.TransactionInput
import app.budgetwise.data.net.TransactionsRequest
import app.budgetwise.data.net.safeApi
import app.budgetwise.data.net.safeApiResponse
import app.budgetwise.data.net.toDomain
import app.budgetwise.data.net.toInput
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val SYNC_LOG_TAG = "BudgetWiseSync"

/** Result of one [SyncEngine.syncOnce] run. Every `pushed` count is outbox ops the server confirmed. */
sealed interface SyncOutcome {
    /** The outbox drained and the pull applied. [pulled] counts local rows the pull inserted, changed or removed. */
    data class Success(val pushed: Int, val pulled: Int) : SyncOutcome

    /** No usable token, or the server answered 401. The outbox is kept; sync pauses until the user signs in again. */
    data object NeedsLogin : SyncOutcome

    /** Network error, 5xx, 408, 429 or an unreadable response. The outbox is kept; retry with backoff. */
    data object RetryLater : SyncOutcome

    /**
     * The server refused [clientIds] for good. Those rows are now FAILED in Room (surfaced in the UI) and their
     * outbox ops are gone, so they are never retried silently. Everything else was pushed and the pull ran.
     */
    data class PermanentFailure(val clientIds: List<String>, val pushed: Int, val pulled: Int) : SyncOutcome
}

/** For the UI, which never reads the network. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus

    /** The latest finished run; [outcome] carries the pushed and pulled counts or the error. */
    data class LastResult(val at: Instant, val outcome: SyncOutcome) : SyncStatus
}

/**
 * Pushes the outbox (in [seq][OutboxEntity.seq] order), then pulls server rows into Room.
 *
 * Conflict rules:
 * - Create: the server wins on an identical clientId (POST is idempotent on it, so resending after a lost
 *   response never duplicates). Each `saved[]` row overwrites the local row: serverId, updatedAt, SYNCED.
 * - Pull merges by clientId. A row with a pending outbox op is never overwritten or removed by a pull: local
 *   intent wins until it is pushed.
 * - Full pull (`fullPull = true`, on app launch and pull-to-refresh) sends no `updatedSince` and removes local
 *   SYNCED rows the server no longer lists, unless they have a pending op. The API has no tombstones, so this
 *   is how deletes made on the web reach the phone (spec amendment 1).
 * - Incremental pull (periodic and after-write) sends `updatedSince` = [PullCursorStore.lastPulledAt], kept at
 *   the newest server updatedAt seen minus [CURSOR_OVERLAP] to tolerate clock skew and out-of-order commits.
 *   It cannot see web deletes; the next full pull does.
 * - Delete of a never-synced row ([enqueueDelete]): remove it locally and drop its outbox CREATE, send nothing.
 *   If a push already claimed that CREATE (it may reach the server while its reply is lost), the row is
 *   tombstoned and the CREATE kept instead, so the push learns the server id and deletes the row there as well.
 * - Delete of a synced row: tombstone it and queue a DELETE by serverId; 404 counts as success.
 * - Only the WS1 server's own rejection marks rows FAILED: error code `validation_failed` (422) or
 *   `invalid_json` (400). The server refuses the whole batch for one bad item, so the batch is resent one item
 *   at a time; only the rejected rows become FAILED (their ops removed, never retried silently) and the rest
 *   are pushed. Every other failure, including a 4xx page from a proxy, firewall or captive network, is
 *   [SyncOutcome.RetryLater] with the outbox kept.
 * - 401 stops the run, keeps the outbox and tells [SessionManager]; the result is [SyncOutcome.NeedsLogin].
 * - A server row the device cannot represent is skipped and logged by server id only (never amounts or text).
 *
 * Every change to a row together with its outbox op happens in one Room transaction. Runs are serialized.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val db: BudgetWiseDb,
    private val api: ApiService,
    private val session: SessionManager,
    private val cursor: PullCursorStore,
    private val clock: Clock,
) {
    private val tx = db.transactionDao()
    private val outbox = db.outboxDao()
    private val mutex = Mutex()

    /** The token this run sends; guarded by [mutex]. A 401 only expires the session that sent it. */
    private var sentToken: String? = null
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun syncOnce(fullPull: Boolean = false): SyncOutcome = mutex.withLock {
        val before = _status.value
        _status.value = SyncStatus.Running
        var outcome: SyncOutcome? = null
        try {
            run(fullPull).also { outcome = it }
        } finally {
            // A cancelled run (worker stopped) leaves the previous result in place.
            _status.value = outcome?.let { SyncStatus.LastResult(clock.instant(), it) } ?: before
        }
    }

    /**
     * Local half of a user delete; the caller schedules a sync afterwards. Safe to call while a sync runs.
     * See the class KDoc for the never-synced and synced cases.
     */
    suspend fun enqueueDelete(clientId: String) {
        db.withTransaction {
            val row = tx.byClientId(clientId)
            when {
                row == null -> outbox.dropFor(clientId)
                row.deleted -> Unit // already on its way out
                row.serverId != null -> {
                    tx.upsert(row.copy(deleted = true, syncState = SyncState.PENDING))
                    outbox.enqueue(OutboxEntity(op = OutboxOp.DELETE, clientId = clientId, serverId = row.serverId))
                }
                outbox.wasSent(clientId) -> tx.upsert(row.copy(deleted = true))
                else -> {
                    outbox.dropFor(clientId)
                    tx.deleteByClientId(clientId)
                }
            }
        }
    }

    /**
     * Empties transactions, outbox and budgets and forgets the pull cursor, under the sync mutex: a run in flight
     * finishes first, and none starts until the wipe is done, so no late ack, delete op or pull can survive it.
     * Used when the data must not outlive its owner (sign-out, signing in as someone else).
     */
    suspend fun wipeLocal() = mutex.withLock {
        db.withTransaction {
            tx.deleteAll()
            outbox.deleteAll()
            db.budgetDao().deleteAll()
        }
        cursor.clearLastPulledAt()
    }

    private class Tally {
        var pushed = 0
        var pulled = 0
        val failed = mutableListOf<String>()
    }

    private suspend fun run(fullPull: Boolean): SyncOutcome {
        sentToken = session.bearer()
        if (sentToken == null) return SyncOutcome.NeedsLogin
        val tally = Tally()
        push(tally)?.let { return it }
        pull(fullPull, tally)?.let { return it }
        return if (tally.failed.isEmpty()) {
            SyncOutcome.Success(tally.pushed, tally.pulled)
        } else {
            SyncOutcome.PermanentFailure(tally.failed.toList(), tally.pushed, tally.pulled)
        }
    }

    // Push. Each step below removes every op it was handed (ack, or FAILED) or returns the outcome that stops
    // the run, so the loop always ends. Ops queued during the run are pushed too.

    private suspend fun push(tally: Tally): SyncOutcome? {
        while (true) {
            val head = claimHead()
            if (head.isEmpty()) return null
            val stop = if (head[0].op == OutboxOp.DELETE) {
                pushDelete(head[0], tally)
            } else {
                pushCreates(head.takeWhile { it.op == OutboxOp.CREATE }, tally)
            }
            if (stop != null) return stop
        }
    }

    /**
     * Reads the head of the outbox and bumps `attempts` on its leading CREATE run in the same transaction, before
     * any row is read or sent. A concurrent [enqueueDelete] (also one transaction) therefore either removes an op
     * before it is claimed or sees it as sent (`attempts > 0`: the server may get the row even if the reply is
     * lost) and keeps it. There is no window where a delete drops an op that is about to go out.
     */
    private suspend fun claimHead(): List<OutboxEntity> = db.withTransaction {
        outbox.head(BATCH_SIZE).also { ops ->
            val creates = ops.takeWhile { it.op == OutboxOp.CREATE }
            if (creates.isNotEmpty()) outbox.bump(creates.map { it.seq })
        }
    }

    private suspend fun pushCreates(ops: List<OutboxEntity>, tally: Tally): SyncOutcome? {
        val batch = ArrayList<Pair<OutboxEntity, TransactionInput>>(ops.size)
        for (op in ops) {
            val row = tx.byClientId(op.clientId)
            if (row == null) {
                outbox.ack(listOf(op.seq)) // the row is gone, nothing to send
                continue
            }
            try {
                batch += op to row.toDomain().toInput()
            } catch (_: IllegalArgumentException) {
                markRejected(op, tally) // no server would accept it (amount not positive)
            }
        }
        return if (batch.isEmpty()) null else pushCreateBatch(batch, tally)
    }

    private suspend fun pushCreateBatch(batch: List<Pair<OutboxEntity, TransactionInput>>, tally: Tally): SyncOutcome? {
        when (val r = safeApi { api.saveTransactions(TransactionsRequest(batch.map { it.second })) }) {
            is ApiResult.Ok -> applySaved(batch.map { it.first }, r.v, tally)
            is ApiResult.Unauthorized -> return needsLogin()
            is ApiResult.Failure -> when {
                !r.rejectsPayload() -> return SyncOutcome.RetryLater
                batch.size == 1 -> markRejected(batch.single().first, tally)
                else -> for (item in batch) pushCreateBatch(listOf(item), tally)?.let { return it }
            }
        }
        return null
    }

    private suspend fun applySaved(ops: List<OutboxEntity>, response: SavedResponse, tally: Tally) {
        val byClientId = response.saved.associateBy { it.clientId }
        val missing = mutableListOf<OutboxEntity>()
        db.withTransaction {
            for (op in ops) {
                val dto = byClientId[op.clientId]
                if (dto == null) {
                    missing += op
                    continue
                }
                outbox.ack(listOf(op.seq))
                val local = tx.byClientId(op.clientId)
                if (local == null || local.deleted) {
                    // Deleted on the device while unconfirmed: now that the server id is known, delete it there too.
                    local?.let { tx.upsert(it.copy(serverId = dto.id)) }
                    if (!outbox.hasPendingFor(op.clientId)) {
                        outbox.enqueue(OutboxEntity(op = OutboxOp.DELETE, clientId = op.clientId, serverId = dto.id))
                    }
                } else {
                    tx.upsert(dto.toEntityOr(local))
                }
                tally.pushed++
            }
        }
        // Not echoed back (deleted on the server between its write and read-back, or a server bug). Keep the row
        // as FAILED rather than resend forever or delete the user's data on the strength of an omission.
        for (op in missing) markRejected(op, tally)
    }

    private suspend fun pushDelete(op: OutboxEntity, tally: Tally): SyncOutcome? {
        val serverId = op.serverId ?: tx.byClientId(op.clientId)?.serverId
        if (serverId != null) {
            outbox.bump(listOf(op.seq))
            when (val r = deleteOnServer(serverId)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Unauthorized -> return needsLogin()
                is ApiResult.Failure -> {
                    if (!r.rejectsPayload()) return SyncOutcome.RetryLater
                    markRejected(op, tally)
                    return null
                }
            }
            tally.pushed++
        }
        db.withTransaction {
            outbox.ack(listOf(op.seq))
            if (!outbox.hasPendingFor(op.clientId)) tx.deleteByClientId(op.clientId)
        }
        return null
    }

    /** 404 means the row is already gone (for example deleted on the web), which is what a DELETE wants. */
    private suspend fun deleteOnServer(serverId: String): ApiResult<Unit> =
        when (val r = safeApi { api.delete(serverId) }) {
            is ApiResult.Ok -> if (r.v.code() == HTTP_NOT_FOUND) ApiResult.Ok(Unit) else safeApiResponse { r.v }
            is ApiResult.Unauthorized -> r
            is ApiResult.Failure -> r
        }

    /** Removes the op and marks the row FAILED; a deleted row the server never stored is simply removed. */
    private suspend fun markRejected(op: OutboxEntity, tally: Tally) {
        val kept = db.withTransaction {
            outbox.ack(listOf(op.seq))
            val local = tx.byClientId(op.clientId) ?: return@withTransaction false
            if (local.deleted && op.op == OutboxOp.CREATE) {
                tx.deleteByClientId(op.clientId)
                false
            } else {
                tx.upsert(local.copy(syncState = SyncState.FAILED, deleted = false))
                true
            }
        }
        if (kept) tally.failed += op.clientId
    }

    private suspend fun needsLogin(): SyncOutcome {
        session.onUnauthorized(sentToken)
        return SyncOutcome.NeedsLogin
    }

    // Pull.

    private suspend fun pull(fullPull: Boolean, tally: Tally): SyncOutcome? {
        val since = if (fullPull) null else cursor.lastPulledAt.first()
        val response = when (val r = safeApi { api.transactions(since?.let(::wireInstant)) }) {
            is ApiResult.Ok -> r.v
            is ApiResult.Unauthorized -> return needsLogin()
            // A GET carries no user data the server could reject, so treat any other failure as transient.
            is ApiResult.Failure -> return SyncOutcome.RetryLater
        }
        val listed = HashSet<String>()
        val rows = ArrayList<Transaction>(response.transactions.size)
        for (dto in response.transactions) {
            listed += dto.clientId // a row we cannot read still exists on the server: never reconcile it away
            try {
                rows += dto.toDomain()
            } catch (_: InvalidWireDataException) {
                // The exception text names the bad value (an amount); log the server id only.
                Log.w(SYNC_LOG_TAG, "Skipped unreadable server row id=${dto.id}")
            }
        }
        tally.pulled = db.withTransaction {
            var changed = 0
            for (t in rows) {
                if (outbox.hasPendingFor(t.clientId)) continue
                val entity = t.toEntity()
                if (tx.byClientId(t.clientId) != entity) {
                    tx.upsert(entity)
                    changed++
                }
            }
            if (fullPull) {
                for (id in tx.syncedClientIds()) {
                    if (id !in listed && !outbox.hasPendingFor(id)) {
                        tx.deleteByClientId(id)
                        changed++
                    }
                }
            }
            changed
        }
        rows.maxOfOrNull { it.updatedAt }?.minus(CURSOR_OVERLAP)?.let { next ->
            if (since == null || next > since) cursor.setLastPulledAt(next)
        }
        return null
    }

    private fun TransactionDto.toEntityOr(local: TransactionEntity): TransactionEntity = try {
        toDomain().toEntity()
    } catch (_: InvalidWireDataException) {
        local.copy(serverId = id, syncState = SyncState.SYNCED)
    }

    companion object {
        /** Server maximum per POST /transactions. */
        const val BATCH_SIZE = 50
        val CURSOR_OVERLAP: Duration = Duration.ofMinutes(2)
        private const val HTTP_NOT_FOUND = 404

        /**
         * Error envelope codes with which the WS1 server itself rejects the request body (zod 422, unparseable JSON
         * 400). Anything else (an HTML 403/405/413 from a proxy, an unreadable 200, a missing route) says nothing
         * about the rows, so it must never mark them FAILED.
         */
        private val SERVER_REJECTION_CODES = setOf("validation_failed", "invalid_json")

        private fun ApiResult.Failure.rejectsPayload() = !retryable && code in SERVER_REJECTION_CODES

        /** UTC with `Z` and millisecond precision; the server rejects offsets. */
        private fun wireInstant(i: Instant): String = i.truncatedTo(ChronoUnit.MILLIS).toString()
    }
}
