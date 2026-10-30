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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.budgetwise.R

/** The kit's weights: 400 to 700, and 800 for the big amounts. */
private val KitWeights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)

private val SchibstedGrotesk = GoogleFont("Schibsted Grotesk")

private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

/**
 * The two families the app draws with. [latin] is Schibsted Grotesk from the Google Fonts provider; until it has
 * downloaded, and on a phone without the provider, Compose draws the system font instead. [myanmar] is the bundled
 * Padauk (SIL), which also covers Basic Latin. Burmese never relies on the system font: Zawgyi-patched or old system
 * fonts mangle Unicode text, so the Burmese UI and Burmese text the user wrote use [myanmar].
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

/** Padauk comes in 400 and 700: 500 draws regular, 600 and 800 draw bold. */
internal fun myanmarFontFamily(): FontFamily = FontFamily(
    Font(R.font.padauk_regular, FontWeight.Normal),
    Font(R.font.padauk_regular, FontWeight.Medium),
    Font(R.font.padauk_bold, FontWeight.SemiBold),
    Font(R.font.padauk_bold, FontWeight.Bold),
    Font(R.font.padauk_bold, FontWeight.ExtraBold),
)

/** A kit size: the font size and line height (sp) one role draws at. */
internal data class Metrics(val size: Int, val lineHeight: Int)

/** Kit size (sp) to line height (sp), from the redesign's English boards. */
internal val LatinMetrics: Map<Int, Metrics> = mapOf(
    12 to 16, 13 to 18, 14 to 20, 15 to 20, 16 to 22, 17 to 22, 18 to 24, 20 to 26, 22 to 28, 24 to 28, 26 to 32,
    32 to 38, 40 to 44, 44 to 52, 60 to 64,
).mapValues { (size, line) -> Metrics(size, line) }

/**
 * The same roles in Burmese, from the redesign's Padauk boards. Small text draws one size up or the same (Padauk's
 * letters sit small in their box), display text well down, and stacked marks get at least 1.7x the size. The 32, 44
 * and 60 roles only show amounts (digits have no stacked marks), so they stay closer to 1.4x.
 */
internal val BurmeseMetrics: Map<Int, Metrics> = mapOf(
    12 to Metrics(13, 22), 13 to Metrics(13, 22), 14 to Metrics(14, 24), 15 to Metrics(15, 26),
    16 to Metrics(16, 28), 17 to Metrics(16, 28), 18 to Metrics(16, 28), 20 to Metrics(18, 32),
    22 to Metrics(18, 32), 24 to Metrics(20, 34), 26 to Metrics(20, 34), 32 to Metrics(24, 42),
    40 to Metrics(26, 44), 44 to Metrics(32, 48), 60 to Metrics(44, 60),
)

/**
 * The kit's type roles, named by their English size and read through [BudgetWiseTheme.type]; each call site sets its
 * weight. [burmese] is true when the roles draw at the Burmese metrics (the phone's language is Burmese).
 */
@Immutable
data class BudgetWiseType(
    val size12: TextStyle,
    val size13: TextStyle,
    val size14: TextStyle,
    val size15: TextStyle,
    val size16: TextStyle,
    val size17: TextStyle,
    val size18: TextStyle,
    val size20: TextStyle,
    val size22: TextStyle,
    val size24: TextStyle,
    val size26: TextStyle,
    val size32: TextStyle,
    val size40: TextStyle,
    val size44: TextStyle,
    val size60: TextStyle,
    val burmese: Boolean,
)

// Letter spacing is zero here (spacing breaks Myanmar shaping; [tracked] adds it to English display text). Leading is
// split evenly and the first and last lines are never trimmed, so stacked marks keep their room.
internal fun kitStyle(family: FontFamily, role: Int, burmese: Boolean): TextStyle {
    val m = (if (burmese) BurmeseMetrics else LatinMetrics).getValue(role)
    return TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = m.size.sp,
        lineHeight = m.lineHeight.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}

fun budgetWiseType(family: FontFamily, burmese: Boolean): BudgetWiseType {
    fun s(role: Int) = kitStyle(family, role, burmese)
    return BudgetWiseType(
        s(12), s(13), s(14), s(15), s(16), s(17), s(18), s(20), s(22), s(24), s(26), s(32), s(40), s(44), s(60),
        burmese,
    )
}

/** Material 3's roles on the kit's sizes, for the Material components the screens still use (dialogs, menus). */
fun materialTypography(t: BudgetWiseType): Typography = Typography(
    displayLarge = t.size60.extraBold,
    displayMedium = t.size44.extraBold,
    displaySmall = t.size32.bold,
    headlineLarge = t.size32.bold,
    headlineMedium = t.size26.bold,
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
val TextStyle.extraBold: TextStyle get() = copy(fontWeight = FontWeight.ExtraBold)

/** The kit's tighter (negative [em]) or wider tracking, for English only: spacing breaks Burmese shaping. */
@Composable
fun TextStyle.tracked(em: Float): TextStyle = if (BudgetWiseTheme.type.burmese) this else copy(letterSpacing = em.em)

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
 * Myanmar font at the Burmese metrics of the same role and no tracking, also in the English UI. A no-op when the UI is
 * already Burmese.
 */
@Composable
fun TextStyle.forContent(text: CharSequence): TextStyle {
    val type = BudgetWiseTheme.type
    if (type.burmese || !containsMyanmar(text)) return this
    val m = BurmeseMetrics[fontSize.value.toInt()]
    return copy(
        fontFamily = BudgetWiseTheme.fonts.myanmar,
        fontSize = m?.size?.sp ?: fontSize,
        lineHeight = m?.lineHeight?.sp ?: (fontSize.value * 1.7f).sp,
        letterSpacing = 0.sp,
    )
}
