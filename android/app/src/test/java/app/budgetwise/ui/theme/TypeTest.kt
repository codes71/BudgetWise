package app.budgetwise.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeTest {
    private val t: Typography = budgetWiseTypography(FontFamily.SansSerif)

    private fun all(): Map<String, TextStyle> = mapOf(
        "displayLarge" to t.displayLarge, "displayMedium" to t.displayMedium, "displaySmall" to t.displaySmall,
        "headlineLarge" to t.headlineLarge, "headlineMedium" to t.headlineMedium, "headlineSmall" to t.headlineSmall,
        "titleLarge" to t.titleLarge, "titleMedium" to t.titleMedium, "titleSmall" to t.titleSmall,
        "bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium, "bodySmall" to t.bodySmall,
        "labelLarge" to t.labelLarge, "labelMedium" to t.labelMedium, "labelSmall" to t.labelSmall,
    )

    @Test
    fun bodyAndLabelLineHeightIsOnePointSixTimesFontSize() {
        listOf(t.bodyLarge, t.bodyMedium, t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall).forEach {
            assertEquals(it.fontSize.value * 1.6f, it.lineHeight.value, 0.51f)
        }
    }

    @Test
    fun everyStyleHasRoomForStackedMarksAndNoLetterSpacing() {
        all().forEach { (name, s) ->
            assertEquals("$name unit", TextUnitType.Sp, s.lineHeight.type)
            assertTrue("$name lineHeight ${s.lineHeight} vs ${s.fontSize}", s.lineHeight.value >= s.fontSize.value * 1.3f)
            assertEquals("$name letterSpacing", 0f, s.letterSpacing.value, 0f)
        }
    }

    @Test
    fun everyStyleUsesTheBundledFamily() {
        all().values.forEach { assertSame(FontFamily.SansSerif, it.fontFamily) }
    }
}
