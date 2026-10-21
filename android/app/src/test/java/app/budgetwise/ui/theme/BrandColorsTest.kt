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
    fun lightSchemeKeepsBrandHuesInContainers() {
        assertEquals(BrandPrimary, LightColorScheme.primaryContainer)
        assertEquals(BrandAccent, LightColorScheme.secondaryContainer)
        assertEquals(BrandCoral, LightColorScheme.tertiaryContainer)
        assertEquals(BrandBackgroundLight, LightColorScheme.background)
        assertEquals(BrandForeground, LightColorScheme.onBackground)
        assertAa(
            "light",
            listOf(
                "primary/surface" to contrast(LightColorScheme.primary, LightColorScheme.surface),
                "secondary/surface" to contrast(LightColorScheme.secondary, LightColorScheme.surface),
                "tertiary/surface" to contrast(LightColorScheme.tertiary, LightColorScheme.surface),
            ),
        )
    }

    @Test
    fun darkSchemeUsesBrandPrimary() {
        assertEquals(BrandPrimary, DarkColorScheme.primary)
        assertEquals(BrandBackgroundDark, DarkColorScheme.background)
        assertAa(
            "dark",
            listOf(
                "primary/surface" to contrast(DarkColorScheme.primary, DarkColorScheme.surface),
                "secondary/surface" to contrast(DarkColorScheme.secondary, DarkColorScheme.surface),
                "tertiary/surface" to contrast(DarkColorScheme.tertiary, DarkColorScheme.surface),
            ),
        )
    }

    @Test
    fun everyTextPairMeetsWcagAa() {
        val light = textPairs(LightColorScheme).map { (pair, ratio) -> "light $pair" to ratio }
        val dark = textPairs(DarkColorScheme).map { (pair, ratio) -> "dark $pair" to ratio }
        assertAa("all", light + dark)
    }

    /** Every text role Material 3 draws, against every background it is drawn on. */
    private fun textPairs(s: ColorScheme): List<Pair<String, Double>> {
        val surfaces = listOf(
            "background" to s.background,
            "surface" to s.surface,
            "surfaceVariant" to s.surfaceVariant,
            "surfaceBright" to s.surfaceBright,
            "surfaceDim" to s.surfaceDim,
            "surfaceContainerLowest" to s.surfaceContainerLowest,
            "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainer" to s.surfaceContainer,
            "surfaceContainerHigh" to s.surfaceContainerHigh,
            "surfaceContainerHighest" to s.surfaceContainerHighest,
        )
        val textOnSurfaces = listOf(
            "onBackground" to s.onBackground,
            "onSurface" to s.onSurface,
            "onSurfaceVariant" to s.onSurfaceVariant,
            "primary" to s.primary,
            "secondary" to s.secondary,
            "tertiary" to s.tertiary,
            "error" to s.error,
        )
        val onSurfaces = surfaces.flatMap { (bgName, bg) ->
            textOnSurfaces.map { (fgName, fg) -> "$fgName/$bgName" to contrast(fg, bg) }
        }
        val roles = listOf(
            "onPrimary/primary" to contrast(s.onPrimary, s.primary),
            "onPrimaryContainer/primaryContainer" to contrast(s.onPrimaryContainer, s.primaryContainer),
            "onSecondary/secondary" to contrast(s.onSecondary, s.secondary),
            "onSecondaryContainer/secondaryContainer" to contrast(s.onSecondaryContainer, s.secondaryContainer),
            "onTertiary/tertiary" to contrast(s.onTertiary, s.tertiary),
            "onTertiaryContainer/tertiaryContainer" to contrast(s.onTertiaryContainer, s.tertiaryContainer),
            "onError/error" to contrast(s.onError, s.error),
            "onErrorContainer/errorContainer" to contrast(s.onErrorContainer, s.errorContainer),
            "inverseOnSurface/inverseSurface" to contrast(s.inverseOnSurface, s.inverseSurface),
            "inversePrimary/inverseSurface" to contrast(s.inversePrimary, s.inverseSurface),
            "onPrimaryFixed/primaryFixed" to contrast(s.onPrimaryFixed, s.primaryFixed),
            "onPrimaryFixed/primaryFixedDim" to contrast(s.onPrimaryFixed, s.primaryFixedDim),
            "onPrimaryFixedVariant/primaryFixed" to contrast(s.onPrimaryFixedVariant, s.primaryFixed),
            "onPrimaryFixedVariant/primaryFixedDim" to contrast(s.onPrimaryFixedVariant, s.primaryFixedDim),
            "onSecondaryFixed/secondaryFixed" to contrast(s.onSecondaryFixed, s.secondaryFixed),
            "onSecondaryFixed/secondaryFixedDim" to contrast(s.onSecondaryFixed, s.secondaryFixedDim),
            "onSecondaryFixedVariant/secondaryFixed" to contrast(s.onSecondaryFixedVariant, s.secondaryFixed),
            "onSecondaryFixedVariant/secondaryFixedDim" to contrast(s.onSecondaryFixedVariant, s.secondaryFixedDim),
            "onTertiaryFixed/tertiaryFixed" to contrast(s.onTertiaryFixed, s.tertiaryFixed),
            "onTertiaryFixed/tertiaryFixedDim" to contrast(s.onTertiaryFixed, s.tertiaryFixedDim),
            "onTertiaryFixedVariant/tertiaryFixed" to contrast(s.onTertiaryFixedVariant, s.tertiaryFixed),
            "onTertiaryFixedVariant/tertiaryFixedDim" to contrast(s.onTertiaryFixedVariant, s.tertiaryFixedDim),
        )
        return onSurfaces + roles
    }

    private fun assertAa(scheme: String, pairs: List<Pair<String, Double>>) {
        val failures = pairs.filter { (_, ratio) -> ratio < 4.5 }
            .map { (pair, ratio) -> "$pair ${"%.2f".format(ratio)}:1" }
        assertTrue("$scheme: below WCAG AA 4.5:1: $failures", failures.isEmpty())
    }

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
