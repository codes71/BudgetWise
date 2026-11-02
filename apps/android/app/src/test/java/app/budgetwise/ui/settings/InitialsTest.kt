package app.budgetwise.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class InitialsTest {
    @Test
    fun theFirstTwoWordsGiveTheInitials() {
        assertEquals("AC", initials(AccountInfo("Aye Chan", "aye.chan@example.com")))
        assertEquals("A", initials(AccountInfo("  aye  ", "aye@example.com")))
        assertEquals("KZ", initials(AccountInfo("Ko Zaw Min", "ko@example.com")))
    }

    @Test
    fun aBurmeseSyllableKeepsItsMarks() {
        // The first user-perceived character of "အေး" is the whole syllable, not the bare consonant.
        assertEquals("အေ", initials(AccountInfo("အေး ချမ်း", "aye@example.com")).take(2))
    }

    @Test
    fun withoutANameTheEmailGivesIt() {
        assertEquals("A", initials(AccountInfo("", "aye@example.com")))
        assertEquals("", initials(AccountInfo("", "")))
    }
}
