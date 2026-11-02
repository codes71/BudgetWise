package app.budgetwise.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class OutboxOp { CREATE, DELETE }

/**
 * A pending write for the server, replayed in [seq] order. A CREATE needs [clientId] (the
 * idempotency key); a DELETE needs [serverId], which is null only if the row was never synced
 * (in which case the caller should drop the pair instead of enqueueing a DELETE). [ownerId] is the
 * account whose token sends it, the row's owner; null only before an account claims it.
 *
 * [replaceGroup] ties an edit together: the CREATEs of the changed entries and the DELETEs of the entries they replace
 * share it. The DELETEs are queued after the CREATEs, so an original is deleted on the server only once its
 * replacement was accepted; when the server refuses one, the group's DELETEs are dropped and the originals come back
 * ([app.budgetwise.data.sync.SyncEngine]). Null for anything else.
 */
@Entity(tableName = "outbox", indices = [Index("clientId"), Index("ownerId")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val op: OutboxOp,
    val clientId: String,
    val ownerId: String?,
    val serverId: String? = null,
    val attempts: Int = 0,
    val replaceGroup: String? = null,
)
