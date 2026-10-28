package app.budgetwise.nlu

import java.time.LocalDate

/** Stub until WS4 lands the real parser. */
object BurmeseBudgetParser {
    fun parse(transcript: String, today: LocalDate, categories: List<String>): List<DraftTransaction> = emptyList()
}
