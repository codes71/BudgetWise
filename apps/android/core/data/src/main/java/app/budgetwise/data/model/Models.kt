package app.budgetwise.data.model

import app.budgetwise.nlu.TxType
import java.time.Instant
import java.time.LocalDate

enum class Source(val wire: String) {
    VOICE("voice"),
    MANUAL("manual"),
    WEB("web"),
}

enum class SyncState { PENDING, SYNCED, FAILED }

data class Transaction(
    val clientId: String,
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
)

/** What was spent on [date] in [category], summed over that day's expense entries. */
data class DailySpend(val date: LocalDate, val category: String, val amountKyat: Long)

/** A monthly spending limit in whole kyat. */
data class Budget(val category: String, val limitKyat: Long)
