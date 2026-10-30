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
 */
@Entity(tableName = "outbox", indices = [Index("clientId"), Index("ownerId")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val op: OutboxOp,
    val clientId: String,
    val ownerId: String?,
    val serverId: String? = null,
    val attempts: Int = 0,
)
