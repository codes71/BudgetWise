package app.budgetwise.ui.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.isAvailableOnDevice
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import app.budgetwise.R

/** The kit's weights: 400, 500, 600 and 700. */
private val KitWeights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

private val SchibstedGrotesk = GoogleFont("Schibsted Grotesk")

private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

/**
 * The two families the app draws with. [latin] is Schibsted Grotesk from the Google Fonts provider; until it has
 * downloaded, and on a phone without the provider, Compose draws the system font instead. [myanmar] is the bundled
 * Noto Sans Myanmar, which also covers Basic Latin. Burmese never relies on the system font: Zawgyi-patched or old
 * system fonts mangle Unicode text, so the Burmese UI and Burmese text the user wrote use [myanmar].
 */
@Immutable
data class BudgetWiseFonts(val latin: FontFamily, val myanmar: FontFamily)

fun budgetWiseFonts(context: Context): BudgetWiseFonts {
    // Without Play services (or under Robolectric) nothing could answer the request: use the system font outright.
    val provider = runCatching { GoogleFontsProvider.isAvailableOnDevice(context) }.getOrDefault(false)
    val latin = if (provider) {
        FontFamily(KitWeights.map { Font(googleFont = SchibstedGrotesk, fontProvider = GoogleFontsProvider, weight = it) })
    } else {
        FontFamily.Default
    }
    return BudgetWiseFonts(latin = latin, myanmar = myanmarFontFamily())
}

/** No Medium file is bundled; 500 uses the regular one, so a Burmese row title reads as regular weight. */
internal fun myanmarFontFamily(): FontFamily = FontFamily(
    Font(R.font.noto_sans_myanmar_regular, FontWeight.Normal),
    Font(R.font.noto_sans_myanmar_regular, FontWeight.Medium),
    Font(R.font.noto_sans_myanmar_semibold, FontWeight.SemiBold),
    Font(R.font.noto_sans_myanmar_bold, FontWeight.Bold),
)

/** Kit size (sp) to line height (sp). Latin uses the kit's English artboards. */
internal val LatinLineHeights = mapOf(12 to 16, 13 to 18, 14 to 20, 15 to 22, 16 to 24, 20 to 28, 25 to 32, 32 to 40, 40 to 48)

/**
 * Burmese stacked marks need at least 1.7x (the kit's Burmese artboards). The 32 and 40 sizes only ever show amounts,
 * and digits have no stacked marks, so they stay closer to the Latin heights.
 */
internal val BurmeseLineHeights = mapOf(12 to 20, 13 to 22, 14 to 24, 15 to 26, 16 to 28, 20 to 34, 25 to 42, 32 to 44, 40 to 52)

/**
 * The kit's type sizes, read through [BudgetWiseTheme.type]; each call site sets its weight. [burmese] is true when the
 * line heights are the Burmese ones (the phone's language is Burmese).
 */
@Immutable
data class BudgetWiseType(
    val size12: TextStyle,
    val size13: TextStyle,
    val size14: TextStyle,
    val size15: TextStyle,
    val size16: TextStyle,
    val size20: TextStyle,
    val size25: TextStyle,
    val size32: TextStyle,
    val size40: TextStyle,
    val burmese: Boolean,
)

// Letter spacing is always zero (spacing breaks Myanmar shaping). Leading is split evenly and the first and last
// lines are never trimmed, so stacked marks keep their room.
internal fun kitStyle(family: FontFamily, size: Int, burmese: Boolean) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = (if (burmese) BurmeseLineHeights else LatinLineHeights).getValue(size).sp,
    letterSpacing = 0.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

fun budgetWiseType(family: FontFamily, burmese: Boolean): BudgetWiseType {
    fun s(size: Int) = kitStyle(family, size, burmese)
    return BudgetWiseType(s(12), s(13), s(14), s(15), s(16), s(20), s(25), s(32), s(40), burmese)
}

/** Material 3's roles on the kit's sizes, for the Material components the screens still use (dialogs, menus). */
fun materialTypography(t: BudgetWiseType): Typography = Typography(
    displayLarge = t.size40.bold,
    displayMedium = t.size32.bold,
    displaySmall = t.size25.bold,
    headlineLarge = t.size32.bold,
    headlineMedium = t.size25.bold,
    headlineSmall = t.size20.semibold,
    titleLarge = t.size20.semibold,
    titleMedium = t.size16.semibold,
    titleSmall = t.size14.semibold,
    bodyLarge = t.size16,
    bodyMedium = t.size15,
    bodySmall = t.size13,
    labelLarge = t.size14.semibold,
    labelMedium = t.size13.medium,
    labelSmall = t.size12.medium,
)

val TextStyle.medium: TextStyle get() = copy(fontWeight = FontWeight.Medium)
val TextStyle.semibold: TextStyle get() = copy(fontWeight = FontWeight.SemiBold)
val TextStyle.bold: TextStyle get() = copy(fontWeight = FontWeight.Bold)

/** Digits of equal width, so amounts line up and do not jump as they change. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/**
 * True for Burmese letters and marks (Myanmar, Extended-A and Extended-B blocks). Myanmar digits alone do not count:
 * they have no stacked marks, and any font draws them.
 */
fun containsMyanmar(text: CharSequence): Boolean = text.any { c ->
    (c in '\u1000'..'\u109F' && c !in '\u1040'..'\u1049' && c !in '\u1090'..'\u1099') ||
        c in '\uAA60'..'\uAA7F' || c in '\uA9E0'..'\uA9FF'
}

/**
 * For text the user wrote (a description, a name, a category of their own): when it has Burmese in it, the bundled
 * Myanmar font and the Burmese line height, also in the English UI. A no-op when the UI is already Burmese.
 */
@Composable
fun TextStyle.forContent(text: CharSequence): TextStyle {
    val type = BudgetWiseTheme.type
    if (type.burmese || !containsMyanmar(text)) return this
    val size = fontSize.value.toInt()
    val lineHeight = BurmeseLineHeights[size]?.sp ?: (fontSize.value * 1.7f).sp
    return copy(fontFamily = BudgetWiseTheme.fonts.myanmar, lineHeight = lineHeight)
}
