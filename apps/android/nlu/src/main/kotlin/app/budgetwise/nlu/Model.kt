package app.budgetwise.nlu

import java.time.LocalDate

enum class TxType { EXPENSE, INCOME }

data class DraftTransaction(
    val type: TxType,
    val amountKyat: Long,
    val category: String,
    val description: String,
    val date: LocalDate,
    val confidence: Float,
    val sourceSpan: IntRange,
)
