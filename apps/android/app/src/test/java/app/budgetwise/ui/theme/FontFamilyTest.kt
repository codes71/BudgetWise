package app.budgetwise.ui.theme

import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class FontFamilyTest {
    private val expected = listOf(FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold)

    private fun weights(): List<FontWeight> {
        val family = budgetWiseFontFamily(ApplicationProvider.getApplicationContext())
        assertTrue(family is FontListFontFamily)
        return (family as FontListFontFamily).fonts.map {
            assertEquals(FontStyle.Normal, it.style)
            it.weight
        }
    }

    @Test @Config(sdk = [29])
    fun customFallbackFamilyHasRegularSemiBoldBold() = assertEquals(expected, weights())

    @Test @Config(sdk = [26])
    fun myanmarOnlyFamilyBelowApi29HasRegularSemiBoldBold() = assertEquals(expected, weights())
}
