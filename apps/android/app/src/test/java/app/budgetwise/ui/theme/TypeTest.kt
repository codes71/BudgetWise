package app.budgetwise.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeTest {
    private val latin = budgetWiseType(FontFamily.SansSerif, burmese = false)
    private val burmese = budgetWiseType(FontFamily.Serif, burmese = true)

    private fun all(t: BudgetWiseType): Map<Int, TextStyle> = mapOf(
        12 to t.size12, 13 to t.size13, 14 to t.size14, 15 to t.size15, 16 to t.size16,
        20 to t.size20, 25 to t.size25, 32 to t.size32, 40 to t.size40,
    )

    @Test
    fun latinLineHeightsAreTheKits() {
        // The kit's English artboards: 15/22, 16/24, 20/28, 40/48 and so on.
        all(latin).forEach { (size, s) ->
            assertEquals("$size sp", size.toFloat(), s.fontSize.value, 0f)
            assertEquals("$size sp", LatinLineHeights.getValue(size).toFloat(), s.lineHeight.value, 0f)
        }
        assertEquals(22f, latin.size15.lineHeight.value, 0f)
        assertEquals(48f, latin.size40.lineHeight.value, 0f)
    }

    @Test
    fun burmeseTextGetsAtLeastOnePointSevenTimesItsSize() {
        // Stacked marks clip below 1.7x. The 32 and 40 sizes only show amounts (digits, no stacked marks).
        all(burmese).filterKeys { it <= 25 }.forEach { (size, s) ->
            assertTrue("$size sp is ${s.lineHeight.value}", s.lineHeight.value >= size * 1.7f - 0.5f)
        }
        assertTrue(burmese.burmese)
        assertFalse(latin.burmese)
    }

    @Test
    fun everyStyleHasRoomForStackedMarksAndNoLetterSpacing() {
        (all(latin) + all(burmese).mapKeys { it.key + 100 }).forEach { (name, s) ->
            assertEquals("$name unit", TextUnitType.Sp, s.lineHeight.type)
            assertEquals("$name lineHeightStyle", LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), s.lineHeightStyle)
            assertEquals("$name letterSpacing", 0f, s.letterSpacing.value, 0f)
        }
    }

    @Test
    fun everyStyleUsesTheGivenFamily() {
        all(latin).values.forEach { assertSame(FontFamily.SansSerif, it.fontFamily) }
        all(burmese).values.forEach { assertSame(FontFamily.Serif, it.fontFamily) }
    }

    @Test
    fun materialRolesSitOnTheKitSizes() {
        val m = materialTypography(latin)
        assertEquals(16f, m.bodyLarge.fontSize.value, 0f)
        assertEquals(15f, m.bodyMedium.fontSize.value, 0f)
        assertEquals(20f, m.titleLarge.fontSize.value, 0f)
        assertEquals(14f, m.labelLarge.fontSize.value, 0f)
        assertSame(FontFamily.SansSerif, m.bodyLarge.fontFamily)
    }

    @Test
    fun burmeseIsFoundInLettersNotInDigitsAlone() {
        assertTrue(containsMyanmar("လက်ဖက်ရည်"))
        assertTrue(containsMyanmar("Grab ခ ၅၀၀၀"))
        assertFalse("Myanmar digits draw in any font", containsMyanmar("Show amounts as ၅,၀၀၀ Ks"))
        assertFalse(containsMyanmar("Shan noodles 4,500 Ks"))
    }
}
