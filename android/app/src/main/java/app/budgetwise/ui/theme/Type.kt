package app.budgetwise.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.graphics.fonts.FontStyle as PlatformFontStyle
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.budgetwise.R

// Static weights only. Open Sans ships no Medium, so Material's Medium roles use SemiBold here.
private val Weights = listOf(FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold)
private val OpenSansRes = intArrayOf(R.font.open_sans_regular, R.font.open_sans_semibold, R.font.open_sans_bold)
private val MyanmarRes = intArrayOf(
    R.font.noto_sans_myanmar_regular,
    R.font.noto_sans_myanmar_semibold,
    R.font.noto_sans_myanmar_bold,
)

/**
 * The app font. API 29+: Open Sans for Latin text with Noto Sans Myanmar as a custom fallback, so mixed text such as
 * "Grab ခ ၅၀၀၀" draws both scripts from the bundled fonts. API 26-28 have no custom-fallback API, so everything uses
 * Noto Sans Myanmar, which also covers all of Basic Latin (checked with fontTools); only the Latin look differs.
 * The system font is never relied on for Burmese because Zawgyi-patched or old system fonts mangle Unicode text.
 */
fun budgetWiseFontFamily(context: Context): FontFamily =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        FontFamily(Weights.indices.map { FallbackFont(Weights[it], openSansWithMyanmarFallback(context, it)) })
    } else {
        FontFamily(Weights.indices.map { Font(MyanmarRes[it], Weights[it], FontStyle.Normal) })
    }

@RequiresApi(Build.VERSION_CODES.Q)
private fun openSansWithMyanmarFallback(context: Context, i: Int): Typeface {
    fun family(res: Int) = PlatformFontFamily.Builder(PlatformFont.Builder(context.resources, res).build()).build()
    return Typeface.CustomFallbackBuilder(family(OpenSansRes[i]))
        .addCustomFallback(family(MyanmarRes[i]))
        .setStyle(PlatformFontStyle(Weights[i].weight, PlatformFontStyle.FONT_SLANT_UPRIGHT))
        .setSystemFallback("sans-serif")
        .build()
}

/** Compose has no per-font fallback chain, so a ready-made platform [Typeface] is wrapped as one weight. */
private class FallbackFont(override val weight: FontWeight, private val typeface: Typeface) :
    AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
    override val style: FontStyle = FontStyle.Normal

    private object Loader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface? = (font as FallbackFont).typeface
        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? = loadBlocking(context, font)
    }
}

private const val LineHeightRatio = 1.6f

// Myanmar stacked marks scale with font size and clip at default heights, so every role uses 1.6x
// (a multiple of the size) and letter spacing is
// always zero (spacing breaks Myanmar shaping).
private fun style(family: FontFamily, weight: FontWeight, size: Float) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * LineHeightRatio).sp,
    letterSpacing = 0.sp,
    // Explicit styles bypass Material 3's default; split leading evenly and never trim the first or last line.
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Material 3 type scale on [family]: every role at 1.6x line height. */
fun budgetWiseTypography(family: FontFamily): Typography {
    val regular = FontWeight.Normal
    val semi = FontWeight.SemiBold
    return Typography(
        displayLarge = style(family, FontWeight.Bold, 57f),
        displayMedium = style(family, FontWeight.Bold, 45f),
        displaySmall = style(family, FontWeight.Bold, 36f),
        headlineLarge = style(family, semi, 32f),
        headlineMedium = style(family, semi, 28f),
        headlineSmall = style(family, semi, 24f),
        titleLarge = style(family, semi, 22f),
        titleMedium = style(family, semi, 16f),
        titleSmall = style(family, semi, 14f),
        bodyLarge = style(family, regular, 16f),
        bodyMedium = style(family, regular, 14f),
        bodySmall = style(family, regular, 12f),
        labelLarge = style(family, semi, 14f),
        labelMedium = style(family, semi, 12f),
        labelSmall = style(family, semi, 11f),
    )
}

@Composable
internal fun rememberBudgetWiseTypography(): Typography {
    val context = LocalContext.current
    return remember(context) { budgetWiseTypography(budgetWiseFontFamily(context)) }
}
