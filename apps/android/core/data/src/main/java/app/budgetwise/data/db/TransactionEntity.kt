package app.budgetwise.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.nlu.TxType
import java.time.Instant
import java.time.LocalDate

/**
 * Local transaction row. [clientId] is the primary key, so it is unique and an upsert with the
 * same clientId replaces the row. [deleted] is a local tombstone, kept until the server confirms
 * the DELETE. [ownerId] is the user id of the account the row belongs to; null only for a row saved
 * before several accounts, until an account claims it ([claimUnowned]).
 */
@Entity(tableName = "tx", indices = [Index("ownerId")])
data class TransactionEntity(
    @PrimaryKey val clientId: String,
    val ownerId: String?,
    val serverId: String?,
    val type: TxType,
    val amountKyat: Long,
    val category: String,
    val description: String,
    val date: LocalDate,
    val source: Source,
    val transcript: String?,
    val updatedAt: Instant,
    val syncState: SyncState,
    val deleted: Boolean = false,
)

fun TransactionEntity.toDomain() = Transaction(
    clientId = clientId,
    serverId = serverId,
    type = type,
    amountKyat = amountKyat,
    category = category,
    description = description,
    date = date,
    source = source,
    transcript = transcript,
    updatedAt = updatedAt,
    syncState = syncState,
)

fun Transaction.toEntity(ownerId: String?, deleted: Boolean = false) = TransactionEntity(
    clientId = clientId,
    ownerId = ownerId,
    serverId = serverId,
    type = type,
    amountKyat = amountKyat,
    category = category,
    description = description,
    date = date,
    source = source,
    transcript = transcript,
    updatedAt = updatedAt,
    syncState = syncState,
    deleted = deleted,
)
