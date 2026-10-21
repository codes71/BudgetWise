package app.budgetwise.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class BrandColorsTest {

    @Test
    fun brandTokensMatchWebTheme() {
        // src/app/globals.css: --primary, --accent, --chart-1, --background, --foreground, .dark --background
        assertEquals(Color(0xFF6CB299), BrandPrimary)
        assertEquals(Color(0xFF588CB6), BrandAccent)
        assertEquals(Color(0xFFE76E50), BrandCoral)
        assertEquals(Color(0xFFF3FCF8), BrandBackgroundLight)
        assertEquals(Color(0xFF030712), BrandForeground)
        assertEquals(Color(0xFF17191C), BrandBackgroundDark)
    }

    @Test
    fun lightSchemeUsesBrandPrimary() {
        assertEquals(BrandPrimary, LightColorScheme.primary)
        assertEquals(BrandBackgroundLight, LightColorScheme.background)
        assertEquals(BrandForeground, LightColorScheme.onBackground)
    }

    @Test
    fun darkSchemeUsesBrandPrimary() {
        assertEquals(BrandPrimary, DarkColorScheme.primary)
        assertEquals(BrandBackgroundDark, DarkColorScheme.background)
    }

    @Test
    fun textOnEverySurfaceMeetsWcagAa() {
        val schemes = listOf("light" to LightColorScheme, "dark" to DarkColorScheme)
        for ((name, scheme) in schemes) {
            // Sage primary as text only passes on the dark surface; the light theme keeps it for fills.
            val extra = if (name == "dark") listOf("primary/surface" to contrast(scheme.primary, scheme.surface)) else emptyList()
            for ((pair, ratio) in textPairs(scheme) + extra) {
                println("$name $pair ${"%.2f".format(ratio)}:1")
                assertTrue("$name $pair is ${"%.2f".format(ratio)}:1, needs 4.5:1", ratio >= 4.5)
            }
        }
    }

    private fun textPairs(s: ColorScheme): List<Pair<String, Double>> = listOf(
        "onPrimary/primary" to contrast(s.onPrimary, s.primary),
        "onPrimaryContainer/primaryContainer" to contrast(s.onPrimaryContainer, s.primaryContainer),
        "onSecondary/secondary" to contrast(s.onSecondary, s.secondary),
        "onSecondaryContainer/secondaryContainer" to contrast(s.onSecondaryContainer, s.secondaryContainer),
        "onTertiary/tertiary" to contrast(s.onTertiary, s.tertiary),
        "onTertiaryContainer/tertiaryContainer" to contrast(s.onTertiaryContainer, s.tertiaryContainer),
        "onError/error" to contrast(s.onError, s.error),
        "onErrorContainer/errorContainer" to contrast(s.onErrorContainer, s.errorContainer),
        "onBackground/background" to contrast(s.onBackground, s.background),
        "onSurface/surface" to contrast(s.onSurface, s.surface),
        "onSurfaceVariant/surface" to contrast(s.onSurfaceVariant, s.surface),
        "onSurfaceVariant/surfaceVariant" to contrast(s.onSurfaceVariant, s.surfaceVariant),
        "onSurface/surfaceContainerLowest" to contrast(s.onSurface, s.surfaceContainerLowest),
        "onSurface/surfaceContainerHighest" to contrast(s.onSurface, s.surfaceContainerHighest),
        "onSurfaceVariant/surfaceContainerHighest" to contrast(s.onSurfaceVariant, s.surfaceContainerHighest),
        "inverseOnSurface/inverseSurface" to contrast(s.inverseOnSurface, s.inverseSurface),
        "onPrimaryFixed/primaryFixed" to contrast(s.onPrimaryFixed, s.primaryFixed),
        "onPrimaryFixedVariant/primaryFixed" to contrast(s.onPrimaryFixedVariant, s.primaryFixed),
        "onSecondaryFixed/secondaryFixed" to contrast(s.onSecondaryFixed, s.secondaryFixed),
        "onSecondaryFixedVariant/secondaryFixed" to contrast(s.onSecondaryFixedVariant, s.secondaryFixed),
        "onTertiaryFixed/tertiaryFixed" to contrast(s.onTertiaryFixed, s.tertiaryFixed),
        "onTertiaryFixedVariant/tertiaryFixed" to contrast(s.onTertiaryFixedVariant, s.tertiaryFixed),
    )

    // WCAG 2.x relative luminance and contrast ratio.
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
