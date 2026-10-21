package app.budgetwise.ui.theme

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

// Tonal steps derived from the brand hues for Material 3 container and surface roles.
internal val SageContainer = Color(0xFFCDEBDD)
internal val SageContainerDark = Color(0xFF1F4F40)
internal val SageFixedDim = Color(0xFFA3D6C1)
internal val SageOnFixedVariant = Color(0xFF2A5E4C)
internal val SageInverse = Color(0xFF2F7A62)
internal val SageInverseDark = Color(0xFF8FD3BA)

internal val BlueContainer = Color(0xFFD6E6F3)
internal val BlueContainerDark = Color(0xFF1D3B53)
internal val BlueOnContainer = Color(0xFF11314B)
internal val BlueFixedDim = Color(0xFFB0CCE4)
internal val BlueOnFixedVariant = Color(0xFF2F5574)

internal val CoralContainer = Color(0xFFFBDCD3)
internal val CoralContainerDark = Color(0xFF6E2A1A)
internal val CoralOnContainer = Color(0xFF5C1D0D)
internal val CoralFixedDim = Color(0xFFF5B6A5)
internal val CoralOnFixedVariant = Color(0xFF7F3220)

internal val ErrorLight = Color(0xFFBA1A1A)
internal val ErrorDark = Color(0xFFFFB4AB)
internal val OnErrorDark = Color(0xFF690005)
internal val ErrorPale = Color(0xFFFFDAD6)
internal val ErrorDeep = Color(0xFF93000A)

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
internal val OutlineVariantDark = Color(0xFF1F2937) // web .dark --border
internal val InverseSurfaceDark = Color(0xFFE2E5E8)
internal val InverseOnSurfaceDark = Color(0xFF2B2E32)
internal val SurfaceBrightDark = Color(0xFF3A3D42)
internal val SurfaceContainerLowestDark = Color(0xFF111316)
internal val SurfaceContainerLowDark = Color(0xFF1D2023)
internal val SurfaceContainerDark = Color(0xFF212427)
internal val SurfaceContainerHighDark = Color(0xFF2B2E32)
internal val SurfaceContainerHighestDark = Color(0xFF33363A)
