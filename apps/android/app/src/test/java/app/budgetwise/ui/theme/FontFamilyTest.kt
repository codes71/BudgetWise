package app.budgetwise.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FontFamilyTest {
    @Test
    fun theMyanmarFamilyHasTheKitsWeightsFromTheBundledFiles() {
        val family = myanmarFontFamily()
        assertTrue(family is FontListFontFamily)
        val fonts = (family as FontListFontFamily).fonts
        assertEquals(
            listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold),
            fonts.map { it.weight },
        )
        fonts.forEach { assertEquals(FontStyle.Normal, it.style) }
        // Never the system font for Burmese: every weight is a bundled Padauk file (it comes in 400 and 700).
        assertEquals(
            listOf(R.font.padauk_regular, R.font.padauk_regular, R.font.padauk_bold, R.font.padauk_bold, R.font.padauk_bold),
            fonts.map { (it as ResourceFont).resId },
        )
    }

    @Test
    fun withoutTheGoogleFontsProviderLatinTextUsesTheSystemFont() {
        // Robolectric has no Play services, like a phone without them.
        val fonts = budgetWiseFonts(ApplicationProvider.getApplicationContext())
        assertSame(FontFamily.Default, fonts.latin)
        assertTrue(fonts.myanmar is FontListFontFamily)
    }
}
