package app.budgetwise.data.model

import app.budgetwise.nlu.TxType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class Source(val wire: String) {
    VOICE("voice"),
    MANUAL("manual"),
    WEB("web"),

    /** Read off a receipt or KPay screen and checked on the review screen. */
    SCAN("scan"),
}

/** One line of a receipt: [quantity] of [name] at [unitPriceKyat] each. */
data class EntryItem(val name: String, val quantity: Int, val unitPriceKyat: Long) {
    /** A line that cost [amountKyat] for one unit, as the scan reads a line with no count. */
    constructor(name: String, amountKyat: Long) : this(name, 1, amountKyat)

    val amountKyat: Long get() = quantity * unitPriceKyat
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
    /** Shared by the entries saved from one scanned receipt; null for anything else. */
    val receiptId: String? = null,
    /** The shop on that receipt. */
    val merchant: String? = null,
    /** The receipt lines this entry is made of; empty when it was not scanned. */
    val items: List<EntryItem> = emptyList(),
)

/** What was spent on [date] in [category], summed over that day's expense entries. */
data class DailySpend(val date: LocalDate, val category: String, val amountKyat: Long)

/** A monthly spending limit in whole kyat. */
data class Budget(val category: String, val limitKyat: Long)

/**
 * One month's budget for an account: [newMoneyKyat] typed for [month] plus [carriedOverKyat] brought over from the
 * month before. [shares] are the categories' parts of the total, in the order the user set them.
 */
data class MonthPlan(
    val month: YearMonth,
    val newMoneyKyat: Long,
    val carriedOverKyat: Long,
    val shares: List<Budget>,
    val endCheck: EndCheck? = null,
) {
    val totalKyat: Long get() = newMoneyKyat + carriedOverKyat
    val splitKyat: Long get() = shares.sumOf { it.limitKyat }

    /** Left to split, shown as "Everything else". Negative when the shares add up to more than the total. */
    val unsplitKyat: Long get() = totalKyat - splitKyat
}

/** What the user said was really on hand after the month ended. */
data class EndCheck(val onHandKyat: Long, val checkedAt: Instant)
