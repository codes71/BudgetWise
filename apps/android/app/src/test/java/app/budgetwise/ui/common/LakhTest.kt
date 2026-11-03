package app.budgetwise.ui.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class LakhFigureTest {
    @Test
    fun wholeLakhHaveNoDecimal() {
        assertEquals("20", lakhFigure(2_000_000))
        assertEquals("1", lakhFigure(100_000))
        assertEquals("0", lakhFigure(0))
    }

    @Test
    fun atMostOneDecimalAndNeverPointZero() {
        assertEquals("22.8", lakhFigure(2_280_000))
        assertEquals("1.5", lakhFigure(150_000))
        assertEquals("18.4", lakhFigure(1_840_000))
        assertEquals("0.5", lakhFigure(50_000))
        // 21.04 lakh rounds to 21, not "21.0".
        assertEquals("21", lakhFigure(2_104_000))
    }

    @Test
    fun roundsHalfUpToTheNearestTenThousand() {
        assertEquals("22.8", lakhFigure(2_284_999))
        assertEquals("22.9", lakhFigure(2_285_000))
        assertEquals("1", lakhFigure(99_999))
        assertEquals("1,000", lakhFigure(99_999_999))
    }

    @Test
    fun largeAmountsAreGroupedAndNegativesKeepTheirSign() {
        assertEquals("1,250", lakhFigure(125_000_000))
        assertEquals("−1.5", lakhFigure(-150_000))
        // Rounded to nothing, there is no sign to show.
        assertEquals("0", lakhFigure(-4_000))
    }

    @Test
    fun followsTheMyanmarDigitsSetting() {
        assertEquals("၂၀", lakhFigure(2_000_000, myanmarDigits = true))
        assertEquals("၂၂.၈", lakhFigure(2_280_000, myanmarDigits = true))
    }
}

/** The word around the figure comes from the app's language: after it in English, သိန်း before it in Burmese. */
@RunWith(RobolectricTestRunner::class)
class LakhTextTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun lakh(amount: Long, myanmarDigits: Boolean = false) =
        context.getString(R.string.lakh_amount, lakhFigure(amount, myanmarDigits))

    @Test
    fun englishPutsLakhAfterTheNumber() {
        assertEquals("20 lakh", lakh(2_000_000))
        assertEquals("22.8 lakh", lakh(2_280_000))
        assertEquals("1.5 lakh", lakh(150_000))
    }

    @Test
    @Config(qualifiers = "my")
    fun burmesePutsThainBeforeTheNumberInEitherDigits() {
        assertEquals("သိန်း ၂၀", lakh(2_000_000, myanmarDigits = true))
        assertEquals("သိန်း ၂၂.၈", lakh(2_280_000, myanmarDigits = true))
        assertEquals("သိန်း 20", lakh(2_000_000))
    }
}
