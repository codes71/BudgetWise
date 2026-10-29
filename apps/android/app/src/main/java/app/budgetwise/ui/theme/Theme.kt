package app.budgetwise.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val LightColorScheme = lightColorScheme(
    primary = SageDeep,
    onPrimary = Color.White,
    primaryContainer = BrandPrimary,
    onPrimaryContainer = BrandForest,
    inversePrimary = SageLight,
    secondary = BlueDeep,
    onSecondary = Color.White,
    secondaryContainer = BrandAccent,
    onSecondaryContainer = BlueInk,
    tertiary = CoralDeep,
    onTertiary = Color.White,
    tertiaryContainer = BrandCoral,
    onTertiaryContainer = CoralInk,
    background = BrandBackgroundLight,
    onBackground = BrandForeground,
    surface = BrandBackgroundLight,
    onSurface = BrandForeground,
    surfaceVariant = NeutralVariantLight,
    onSurfaceVariant = OnNeutralVariantLight,
    surfaceTint = SageDeep,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorPale,
    onErrorContainer = ErrorDeep,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    scrim = Color.Black,
    surfaceBright = BrandBackgroundLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainerLowest = Color.White,
    surfaceDim = SurfaceDimLight,
    primaryFixed = SageContainer,
    primaryFixedDim = SageFixedDim,
    onPrimaryFixed = BrandForest,
    onPrimaryFixedVariant = SageOnFixedVariant,
    secondaryFixed = BlueContainer,
    secondaryFixedDim = BlueFixedDim,
    onSecondaryFixed = BlueOnFixed,
    onSecondaryFixedVariant = BlueOnFixedVariant,
    tertiaryFixed = CoralContainer,
    tertiaryFixedDim = CoralFixedDim,
    onTertiaryFixed = CoralOnFixed,
    onTertiaryFixedVariant = CoralOnFixedVariant,
)

internal val DarkColorScheme = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandForest,
    primaryContainer = SageContainerDark,
    onPrimaryContainer = SageContainer,
    inversePrimary = SageDeep,
    secondary = BlueLight,
    onSecondary = BrandForeground,
    secondaryContainer = BlueContainerDark,
    onSecondaryContainer = BlueContainer,
    tertiary = CoralLight,
    onTertiary = BrandForeground,
    tertiaryContainer = CoralContainerDark,
    onTertiaryContainer = CoralContainer,
    background = BrandBackgroundDark,
    onBackground = BrandForegroundDark,
    surface = BrandBackgroundDark,
    onSurface = BrandForegroundDark,
    surfaceVariant = NeutralVariantDark,
    onSurfaceVariant = OnNeutralVariantDark,
    surfaceTint = BrandPrimary,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorPale,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    scrim = Color.Black,
    surfaceBright = SurfaceBrightDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceDim = BrandBackgroundDark,
    primaryFixed = SageContainer,
    primaryFixedDim = SageFixedDim,
    onPrimaryFixed = BrandForest,
    onPrimaryFixedVariant = SageOnFixedVariant,
    secondaryFixed = BlueContainer,
    secondaryFixedDim = BlueFixedDim,
    onSecondaryFixed = BlueOnFixed,
    onSecondaryFixedVariant = BlueOnFixedVariant,
    tertiaryFixed = CoralContainer,
    tertiaryFixedDim = CoralFixedDim,
    onTertiaryFixed = CoralOnFixed,
    onTertiaryFixedVariant = CoralOnFixedVariant,
)

// The kit's corners: fields 14, notices 16, cards and grouped rows 18, sheets and dialogs 28.
internal val BudgetWiseShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LocalBudgetWiseColors = staticCompositionLocalOf { DarkBudgetWiseColors }
private val LocalBudgetWiseType = staticCompositionLocalOf<BudgetWiseType> { error("BudgetWiseType outside BudgetWiseTheme") }
private val LocalBudgetWiseFonts = staticCompositionLocalOf<BudgetWiseFonts> { error("BudgetWiseFonts outside BudgetWiseTheme") }

/** The redesign kit's tokens and type sizes, for the composables inside [BudgetWiseTheme]. */
object BudgetWiseTheme {
    val colors: BudgetWiseColors
        @Composable @ReadOnlyComposable get() = LocalBudgetWiseColors.current

    val type: BudgetWiseType
        @Composable @ReadOnlyComposable get() = LocalBudgetWiseType.current

    val fonts: BudgetWiseFonts
        @Composable @ReadOnlyComposable get() = LocalBudgetWiseFonts.current
}

/**
 * The BudgetWise theme: the kit's dark tokens by default and its light ones in light mode, following the system.
 * Dynamic color is off on purpose: the brand must look the same on every phone, so the schemes never come from the
 * wallpaper. A Burmese UI draws with the bundled Myanmar font and the taller Burmese line heights.
 */
@Composable
fun BudgetWiseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val burmese = LocalConfiguration.current.locales[0].language == "my"
    val fonts = remember(context) { budgetWiseFonts(context) }
    val type = remember(fonts, burmese) { budgetWiseType(if (burmese) fonts.myanmar else fonts.latin, burmese) }
    CompositionLocalProvider(
        LocalBudgetWiseColors provides if (darkTheme) DarkBudgetWiseColors else LightBudgetWiseColors,
        LocalBudgetWiseType provides type,
        LocalBudgetWiseFonts provides fonts,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = remember(type) { materialTypography(type) },
            shapes = BudgetWiseShapes,
            content = content,
        )
    }
}
