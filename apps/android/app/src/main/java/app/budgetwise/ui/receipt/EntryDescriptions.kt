package app.budgetwise.ui.receipt

import app.budgetwise.data.repo.TransactionRepository
import app.budgetwise.data.text.BurmeseTextNormalizer

/** One language's way to write a receipt entry's description: [pattern] (scan_entry_description) and each [label]. */
class DescriptionLanguage(val pattern: String, val label: (category: String) -> String)

/**
 * The descriptions the app writes for a receipt's entries ("City Mart, Groceries"), as the scan stores them (prepared,
 * then cut). [now] is the app's language; [others] the rest it is written in, so a description written before a
 * language switch, or on a phone set to the other language, is still known as the app's own.
 */
class EntryDescriptions(private val now: DescriptionLanguage, private val others: List<DescriptionLanguage> = emptyList()) {
    /** [merchant] and [category] in the app's language now. */
    fun describe(merchant: String, category: String): String = now.write(merchant, category)

    /**
     * Whether [description] is one the app wrote for [merchant] (the shop as stored) and [category], in any language.
     * A shop name cut to fit when it was stored is whole in the description, which then starts with the cut name.
     */
    fun wrote(description: String, merchant: String, category: String): Boolean = (listOf(now) + others).any { language ->
        description == language.write(merchant, category) ||
            merchant.length >= TransactionRepository.MAX_MERCHANT && description.startsWith(merchant) &&
            description.endsWith(BurmeseTextNormalizer.prepare(language.pattern.format("", language.label(category))))
    }

    private fun DescriptionLanguage.write(merchant: String, category: String): String =
        BurmeseTextNormalizer.prepare(pattern.format(merchant, label(category))).take(TransactionRepository.MAX_DESCRIPTION)
}
