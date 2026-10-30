package app.budgetwise.ui.voice

import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.repo.VoiceMetric
import app.budgetwise.data.repo.VoiceQuestion
import app.budgetwise.nlu.TxType
import java.time.LocalDate
import java.time.YearMonth

/** One category's spending in the period; [limitKyat] only for a budget question. */
data class CategoryTotal(val category: String, val amountKyat: Long, val limitKyat: Long? = null) {
    val over: Boolean get() = limitKyat != null && amountKyat > limitKyat
}

/**
 * The answer to a spoken question, worked out on the phone from Room (the server only read the question). Spending is
 * expenses only. For [VoiceMetric.OVER_BUDGET] the period is the month of [VoiceQuestion.to] up to that day, since
 * budgets are monthly, and [byCategory] holds the budgeted categories with their limits.
 */
data class VoiceAnswer(
    val question: VoiceQuestion,
    val from: LocalDate,
    val to: LocalDate,
    val totalKyat: Long,
    /** Largest first; over-budget ones first for a budget question. */
    val byCategory: List<CategoryTotal>,
    /** The period's expenses, newest first. */
    val entries: List<Transaction>,
) {
    val overCount: Int get() = byCategory.count { it.over }
}

fun answer(question: VoiceQuestion, transactions: List<Transaction>, budgets: List<Budget>): VoiceAnswer {
    val budgetQuestion = question.metric == VoiceMetric.OVER_BUDGET
    val from = if (budgetQuestion) YearMonth.from(question.to).atDay(1) else question.from
    val to = question.to
    val entries = transactions
        .filter { it.type == TxType.EXPENSE && !it.date.isBefore(from) && !it.date.isAfter(to) }
        .filter { question.category == null || it.category == question.category }
        .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.updatedAt })
    val spent = entries.groupBy { it.category }.mapValues { (_, rows) -> rows.sumOf { it.amountKyat } }
    val byCategory = if (budgetQuestion) {
        budgets
            .filter { question.category == null || it.category == question.category }
            .map { CategoryTotal(it.category, spent[it.category] ?: 0, it.limitKyat) }
            .sortedWith(compareByDescending<CategoryTotal> { it.over }.thenByDescending { it.amountKyat })
    } else {
        spent.map { (category, amount) -> CategoryTotal(category, amount) }.sortedByDescending { it.amountKyat }
    }
    return VoiceAnswer(question, from, to, entries.sumOf { it.amountKyat }, byCategory, entries)
}
