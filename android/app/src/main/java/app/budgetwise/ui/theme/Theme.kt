package app.budgetwise.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val LightColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandForest,
    primaryContainer = SageContainer,
    onPrimaryContainer = BrandForest,
    inversePrimary = SageInverseDark,
    secondary = BrandAccent,
    onSecondary = BrandForeground,
    secondaryContainer = BlueContainer,
    onSecondaryContainer = BlueOnContainer,
    tertiary = BrandCoral,
    onTertiary = BrandForeground,
    tertiaryContainer = CoralContainer,
    onTertiaryContainer = CoralOnContainer,
    background = BrandBackgroundLight,
    onBackground = BrandForeground,
    surface = BrandBackgroundLight,
    onSurface = BrandForeground,
    surfaceVariant = NeutralVariantLight,
    onSurfaceVariant = OnNeutralVariantLight,
    surfaceTint = BrandPrimary,
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
    onSecondaryFixed = BlueOnContainer,
    onSecondaryFixedVariant = BlueOnFixedVariant,
    tertiaryFixed = CoralContainer,
    tertiaryFixedDim = CoralFixedDim,
    onTertiaryFixed = CoralOnContainer,
    onTertiaryFixedVariant = CoralOnFixedVariant,
)

internal val DarkColorScheme = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandForest,
    primaryContainer = SageContainerDark,
    onPrimaryContainer = SageContainer,
    inversePrimary = SageInverse,
    secondary = BrandAccent,
    onSecondary = BrandForeground,
    secondaryContainer = BlueContainerDark,
    onSecondaryContainer = BlueContainer,
    tertiary = BrandCoral,
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
    errorContainer = ErrorDeep,
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
    onSecondaryFixed = BlueOnContainer,
    onSecondaryFixedVariant = BlueOnFixedVariant,
    tertiaryFixed = CoralContainer,
    tertiaryFixedDim = CoralFixedDim,
    onTertiaryFixed = CoralOnContainer,
    onTertiaryFixedVariant = CoralOnFixedVariant,
)

// Web --radius is 0.5rem: rounded-sm 4, rounded-md 6, rounded-lg 8.
internal val BudgetWiseShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
)

/**
 * The BudgetWise Material 3 theme. Dynamic color is off on purpose: the brand must look the same on
 * every phone, so the schemes never come from the wallpaper.
 */
@Composable
fun BudgetWiseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        shapes = BudgetWiseShapes,
        content = content,
    )
}
