package app.budgetwise.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Brand tokens: exact copies of the web theme in src/app/globals.css. Change both together.
val BrandPrimary = Color(0xFF6CB299) // --primary, hsl(159 31% 56%), sage
val BrandAccent = Color(0xFF588CB6) // --accent, hsl(207 39% 53%), blue
val BrandCoral = Color(0xFFE76E50) // --chart-1, hsl(12 76% 61%)
val BrandBackgroundLight = Color(0xFFF3FCF8) // --background, hsl(156 60% 97%)
val BrandForeground = Color(0xFF030712) // --foreground, hsl(224 71.4% 4.1%)
val BrandBackgroundDark = Color(0xFF17191C) // .dark --background, hsl(220 10% 10%)
val BrandForegroundDark = Color(0xFFF9FAFB) // .dark --foreground, hsl(210 20% 98%)

// Forest: the launcher icon background (brand/README.md) and the text color on sage, which is too
// light for white text (2.5:1).
val BrandForest = Color(0xFF143429)

// Light scheme text roles (primary, secondary, tertiary): the brand hues darkened until they pass WCAG AA
// 4.5:1 on every light surface. The exact brand hues fill the containers instead.
internal val SageDeep = Color(0xFF26644E) // hsl(159 45% 27%)
internal val BlueDeep = Color(0xFF305B7E) // hsl(207 45% 34%)
internal val CoralDeep = Color(0xFF9C351C) // hsl(12 70% 36%)
internal val BlueInk = Color(0xFF081A2A) // text on the brand blue container
internal val CoralInk = Color(0xFF3A1006) // text on the brand coral container

// Dark scheme text roles: sage passes on every dark surface as is; blue and coral are lightened to pass.
internal val BlueLight = Color(0xFF89B1D2) // hsl(207 45% 68%)
internal val CoralLight = Color(0xFFF08E75) // hsl(12 80% 70%)

// Tonal steps derived from the brand hues for the remaining Material 3 container and fixed roles.
internal val SageContainer = Color(0xFFCDEBDD)
internal val SageContainerDark = Color(0xFF1F4F40)
internal val SageFixedDim = Color(0xFFA3D6C1)
internal val SageOnFixedVariant = Color(0xFF2A5E4C)
internal val SageLight = Color(0xFF8FD3BA)

internal val BlueContainer = Color(0xFFD6E6F3)
internal val BlueContainerDark = Color(0xFF1D3B53)
internal val BlueOnFixed = Color(0xFF11314B)
internal val BlueFixedDim = Color(0xFFB0CCE4)
internal val BlueOnFixedVariant = Color(0xFF2F5574)

internal val CoralContainer = Color(0xFFFBDCD3)
internal val CoralContainerDark = Color(0xFF6E2A1A)
internal val CoralOnFixed = Color(0xFF5C1D0D)
internal val CoralFixedDim = Color(0xFFF5B6A5)
internal val CoralOnFixedVariant = Color(0xFF7F3220)

internal val ErrorLight = Color(0xFFBA1A1A)
internal val ErrorDark = Color(0xFFFFB4AB)
internal val OnErrorDark = Color(0xFF690005)
internal val ErrorPale = Color(0xFFFFDAD6)
internal val ErrorDeep = Color(0xFF93000A)
internal val ErrorContainerDark = Color(0xFF4A1C17)

// Light neutrals carry a trace of the sage hue; dark neutrals follow the web's cool grey (hue 220).
internal val NeutralVariantLight = Color(0xFFDCE8E2)
internal val OnNeutralVariantLight = Color(0xFF3F4945)
internal val OutlineLight = Color(0xFF6F7A74)
internal val OutlineVariantLight = Color(0xFFE5E7EB) // web --border
internal val InverseSurfaceLight = Color(0xFF2B322F)
internal val InverseOnSurfaceLight = Color(0xFFECF3EF)
internal val SurfaceDimLight = Color(0xFFD3DDD8)
internal val SurfaceContainerLowLight = Color(0xFFEDF7F2)
internal val SurfaceContainerLight = Color(0xFFE7F1EC)
internal val SurfaceContainerHighLight = Color(0xFFE1EBE6)
internal val SurfaceContainerHighestLight = Color(0xFFDCE6E1)

internal val NeutralVariantDark = Color(0xFF2A2E33)
internal val OnNeutralVariantDark = Color(0xFF9CA3AF) // web .dark --muted-foreground
internal val OutlineDark = Color(0xFF8B929B)
internal val OutlineVariantDark = Color(0xFF2F3338) // the redesign's hairline
internal val InverseSurfaceDark = Color(0xFFE2E5E8)
internal val InverseOnSurfaceDark = Color(0xFF2B2E32)
internal val SurfaceBrightDark = Color(0xFF34373B)
internal val SurfaceContainerLowestDark = Color(0xFF111316)
internal val SurfaceContainerLowDark = Color(0xFF1D2023)
internal val SurfaceContainerDark = Color(0xFF212427)
internal val SurfaceContainerHighDark = Color(0xFF2B2E32)
internal val SurfaceContainerHighestDark = Color(0xFF303337)

/**
 * The redesign kit's tokens, read through [BudgetWiseTheme.colors]. Material 3's scheme carries the same values where
 * it has a role for them; these name what it has no role for (the hairline, the notice card, the chart marks).
 */
@Immutable
data class BudgetWiseColors(
    /** The screen behind everything. */
    val ground: Color,
    /** Cards, grouped rows and fields. */
    val surface: Color,
    /** On top of a surface: bar tracks, skeleton bars, the disabled button, sheets and dialogs. */
    val raised: Color,
    /** Dividers and outlines. */
    val hairline: Color,
    val fieldBorder: Color,
    val chipBorder: Color,
    val navBackground: Color,
    val navBorder: Color,
    /** A secondary button's outline: stronger than the hairline so the button reads as one. */
    val outline: Color,
    /** Behind a two-way switch (the spending view, expense or income). */
    val segmentTrack: Color,
    /** Inside a category badge's outlined circle. */
    val badgeFill: Color,
    val ink: Color,
    val muted: Color,
    val disabled: Color,
    /** A future day in the date picker. */
    val unavailable: Color,
    /** Sage as text, icons, focus borders and progress. */
    val accent: Color,
    /** The primary button and the Add pill. */
    val sageFill: Color,
    val onSageFill: Color,
    val sageContainer: Color,
    val onSageContainer: Color,
    /** Spending marks: the chart line and bars, an over-budget bar. */
    val coral: Color,
    /** Coral as text: over budget, attention. */
    val coralText: Color,
    val coralBorder: Color,
    val expenseSelected: Color,
    val onExpenseSelected: Color,
    /** Offline and waiting. */
    val blue: Color,
    val blueBorder: Color,
    val error: Color,
    val errorBorder: Color,
    val deleteReveal: Color,
    val onDeleteReveal: Color,
    val notice: Color,
    val noticeBorder: Color,
    val chartLastMonth: Color,
    val chartTick: Color,
    val otherCategories: Color,
    val scrim: Color,
    /** The dark set: picks the dark badge tones. */
    val isDark: Boolean,
)

internal val DarkBudgetWiseColors = BudgetWiseColors(
    ground = BrandBackgroundDark,
    surface = SurfaceContainerDark,
    raised = SurfaceContainerHighDark,
    hairline = OutlineVariantDark,
    fieldBorder = OutlineVariantDark,
    chipBorder = OutlineVariantDark,
    navBackground = BrandBackgroundDark,
    navBorder = OutlineVariantDark,
    outline = Color(0xFF4A4F56),
    segmentTrack = SurfaceContainerDark,
    badgeFill = Color.Transparent,
    ink = BrandForegroundDark,
    muted = OnNeutralVariantDark,
    disabled = OutlineDark,
    unavailable = Color(0xFF5B626B),
    accent = BrandPrimary,
    sageFill = BrandPrimary,
    onSageFill = BrandForest,
    sageContainer = SageContainerDark,
    onSageContainer = SageContainer,
    coral = CoralLight,
    coralText = CoralLight,
    coralBorder = CoralContainerDark,
    expenseSelected = CoralContainerDark,
    onExpenseSelected = Color(0xFFF8C3B4),
    blue = BlueLight,
    blueBorder = BlueContainerDark,
    error = ErrorDark,
    errorBorder = ErrorContainerDark,
    deleteReveal = Color(0xFF8C1D18),
    onDeleteReveal = ErrorPale,
    notice = SurfaceContainerHighDark,
    noticeBorder = OutlineVariantDark,
    chartLastMonth = OutlineDark,
    chartTick = BrandForegroundDark,
    otherCategories = Color(0xFF5B626B),
    scrim = Color(0x9E0A0B0D),
    isDark = true,
)

internal val LightBudgetWiseColors = BudgetWiseColors(
    ground = BrandBackgroundLight,
    surface = Color.White,
    raised = SurfaceContainerLight,
    hairline = OutlineVariantLight,
    fieldBorder = NeutralVariantLight,
    chipBorder = NeutralVariantLight,
    navBackground = BrandBackgroundLight,
    navBorder = OutlineVariantLight,
    outline = Color(0xFFC5D1CB),
    segmentTrack = Color(0xFFE3ECE7),
    badgeFill = Color.White,
    ink = BrandForeground,
    muted = OnNeutralVariantLight,
    disabled = OutlineLight,
    unavailable = Color(0xFFA3ADA8),
    accent = SageDeep,
    sageFill = BrandPrimary,
    onSageFill = BrandForest,
    sageContainer = SageContainer,
    onSageContainer = BrandForest,
    coral = BrandCoral,
    coralText = CoralDeep,
    coralBorder = CoralContainer,
    expenseSelected = CoralContainer,
    onExpenseSelected = Color(0xFF93331B),
    blue = BlueDeep,
    blueBorder = BlueContainer,
    error = ErrorLight,
    errorBorder = ErrorPale,
    deleteReveal = ErrorLight,
    onDeleteReveal = Color.White,
    notice = Color.White,
    noticeBorder = OutlineVariantLight,
    chartLastMonth = OutlineLight,
    chartTick = BrandForeground,
    otherCategories = Color(0xFFA3ADA8),
    scrim = Color(0x52030712),
    isDark = false,
)
