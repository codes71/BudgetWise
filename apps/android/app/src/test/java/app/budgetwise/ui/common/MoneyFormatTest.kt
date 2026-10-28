package app.budgetwise.ui.common

import app.budgetwise.nlu.TxType
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFormatTest {
    @Test
    fun formatsGroupingAndSuffix() {
        assertEquals("5,000 Ks", formatKyat(5000))
        assertEquals("1,234,567 Ks", formatKyat(1234567))
        assertEquals("0 Ks", formatKyat(0))
        assertEquals("999 Ks", formatKyat(999))
        assertEquals("9,007,199,254,740,991 Ks", formatKyat(MAX_KYAT))
        // A negative balance keeps the grouping and gets a real minus sign.
        assertEquals("−5,000 Ks", formatKyat(-5000))
    }

    @Test
    fun myanmarDigits() {
        assertEquals("၅,၀၀၀ Ks", formatKyat(5000, myanmarDigits = true))
        assertEquals("၁,၂၃၄,၅၆၇ Ks", formatKyat(1234567, myanmarDigits = true))
        assertEquals("၅,၀၀၀", groupDigits(5000, myanmarDigits = true))
    }

    @Test
    fun signedAmountsNeverRelyOnColorAlone() {
        assertEquals("−5,000 Ks", formatSignedKyat(5000, TxType.EXPENSE))
        assertEquals("+5,000 Ks", formatSignedKyat(5000, TxType.INCOME))
        assertEquals("+၅,၀၀၀ Ks", formatSignedKyat(5000, TxType.INCOME, myanmarDigits = true))
    }

    @Test
    fun typedDigitsAcceptBurmeseKeyboards() {
        assertEquals("5000", asciiDigits("၅၀၀၀"))
        assertEquals("5000", asciiDigits("5,0၀0 Ks"))
        assertEquals("", asciiDigits("abc"))
    }
}
