package app.budgetwise.nlu

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContractShapeTest {
    @Test
    fun draftTransactionMatchesSharedContract() {
        val draft = DraftTransaction(
            type = TxType.EXPENSE,
            amountKyat = 3000L,
            category = "Food and Drink Item",
            description = "coffee",
            date = LocalDate.of(2026, 10, 19),
            confidence = 0.9f,
            sourceSpan = 0..5,
        )

        assertEquals(TxType.EXPENSE, draft.type)
        assertEquals(3000L, draft.amountKyat)
        assertEquals("Food and Drink Item", draft.category)
        assertEquals("coffee", draft.description)
        assertEquals(LocalDate.of(2026, 10, 19), draft.date)
        assertEquals(0.9f, draft.confidence)
        assertEquals(0..5, draft.sourceSpan)
        assertEquals(listOf(TxType.EXPENSE, TxType.INCOME), TxType.entries.toList())
    }

    @Test
    fun parserEntryPointsMatchSharedContract() {
        val parse: (String, LocalDate, List<String>) -> List<DraftTransaction> = BurmeseBudgetParser::parse
        val number: (String) -> Long? = BurmeseNumber::parse

        parse("", LocalDate.of(2026, 10, 19), listOf("Other"))
        number("")
    }
}
