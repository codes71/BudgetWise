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
        12 to t.size12, 13 to t.size13, 14 to t.size14, 15 to t.size15, 16 to t.size16, 17 to t.size17,
        18 to t.size18, 20 to t.size20, 22 to t.size22, 24 to t.size24, 26 to t.size26, 32 to t.size32,
        40 to t.size40, 44 to t.size44, 60 to t.size60,
    )

    @Test
    fun latinRolesAreTheRedesignsEnglishSizes() {
        // The English boards: 12/16, 16/22, 26/32, 60/64 and so on.
        all(latin).forEach { (role, s) ->
            assertEquals("$role sp", role.toFloat(), s.fontSize.value, 0f)
            assertEquals("$role sp", LatinMetrics.getValue(role).lineHeight.toFloat(), s.lineHeight.value, 0f)
        }
        assertEquals(22f, latin.size16.lineHeight.value, 0f)
        assertEquals(64f, latin.size60.lineHeight.value, 0f)
    }

    @Test
    fun burmeseRolesAreThePadaukBoardsSizes() {
        all(burmese).forEach { (role, s) ->
            val m = BurmeseMetrics.getValue(role)
            assertEquals("$role sp", m.size.toFloat(), s.fontSize.value, 0f)
            assertEquals("$role sp", m.lineHeight.toFloat(), s.lineHeight.value, 0f)
        }
        // Padauk's letters sit small: small text is never smaller than the English, display text is much smaller.
        assertEquals(13f, burmese.size12.fontSize.value, 0f)
        assertEquals(20f, burmese.size26.fontSize.value, 0f)
        assertEquals(44f, burmese.size60.fontSize.value, 0f)
    }

    @Test
    fun burmeseTextGetsAtLeastOnePointSevenTimesItsSize() {
        // Stacked marks clip below 1.7x. The 32, 44 and 60 roles only show amounts (digits, no stacked marks).
        all(burmese).filterKeys { it <= 26 || it == 40 }.forEach { (role, s) ->
            assertTrue("$role is ${s.fontSize} on ${s.lineHeight}", s.lineHeight.value >= s.fontSize.value * 1.7f - 0.5f)
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
