package app.budgetwise.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.ui.theme.BudgetWiseTheme

/** A badge's fill and the icon drawn on it. */
data class BadgeColors(val container: Color, val content: Color)

/** How a built-in category looks: its icon, its translated name, and a soft tone per theme. */
data class CategoryStyle(
    val icon: ImageVector,
    @StringRes val label: Int,
    val light: BadgeColors,
    val dark: BadgeColors,
)

private fun tone(container: Long, content: Long) = BadgeColors(Color(container), Color(content))

// One hue per category (the redesign kit's badges), chosen to stay apart from each other and from the error red. Light:
// a pale tint with a deep icon; dark: a deep tint with a pale icon. Every icon is at least 5.7:1 on its fill
// (CategoryStyleTest).
private val Styles: Map<String, CategoryStyle> = mapOf(
    "Groceries" to CategoryStyle(
        Lucide.Cart, R.string.category_groceries,
        tone(0xFFD3EFDF, 0xFF1D6544), tone(0xFF1C4434, 0xFF9FDCC0),
    ),
    "Utilities" to CategoryStyle(
        Lucide.Zap, R.string.category_utilities,
        tone(0xFFFAEBC2, 0xFF704C00), tone(0xFF463712, 0xFFF2CD6B),
    ),
    "Entertainment" to CategoryStyle(
        Lucide.Film, R.string.category_entertainment,
        tone(0xFFE8DEF8, 0xFF55398F), tone(0xFF382B55, 0xFFCDB9F2),
    ),
    "Transport" to CategoryStyle(
        Lucide.Bus, R.string.category_transport,
        tone(0xFFD6E6F3, 0xFF2C5679), tone(0xFF1D3B53, 0xFFA9C9E6),
    ),
    "Housing" to CategoryStyle(
        Lucide.Home, R.string.category_housing,
        tone(0xFFF2E1D3, 0xFF6E4226), tone(0xFF47301F, 0xFFE8C3A6),
    ),
    "Health" to CategoryStyle(
        Lucide.Cross, R.string.category_health,
        tone(0xFFF9DCE4, 0xFF932A47), tone(0xFF55202E, 0xFFF4B3C3),
    ),
    "Food and Drink Item" to CategoryStyle(
        Lucide.Utensils, R.string.category_food_and_drink,
        tone(0xFFFBDCD3, 0xFF93331B), tone(0xFF6E2A1A, 0xFFF8C3B4),
    ),
    "Other" to CategoryStyle(
        Lucide.Shapes, R.string.category_other,
        tone(0xFFD2EBEC, 0xFF1C5C5E), tone(0xFF1B4143, 0xFF9ED5D6),
    ),
    // Not an expense chip: every income entry is filed under it, on the web and the phone.
    CategoryRepository.INCOME to CategoryStyle(
        Lucide.ArrowDownLeft, R.string.category_income,
        tone(0xFFE2F0D0, 0xFF3D5A12), tone(0xFF2F4019, 0xFFC5E0A0),
    ),
)

/**
 * The style of one of the eight built-in categories or the web's "Income" (exact server names), or null for a user's
 * own category.
 */
fun builtInCategoryStyle(category: String): CategoryStyle? = Styles[category]

/**
 * The name to show. Built-in categories are translated; the stored and synced name stays the English one the server
 * knows. A user's own category is shown as typed on the web.
 */
@Composable
fun categoryLabel(category: String): String = builtInCategoryStyle(category)?.let { stringResource(it.label) } ?: category

/** Badge colors for [category] in the current theme; a user's own category gets the neutral tone. */
@Composable
fun categoryColors(category: String): BadgeColors {
    val colors = BudgetWiseTheme.colors
    val style = builtInCategoryStyle(category) ?: return BadgeColors(colors.raised, colors.muted)
    return if (colors.isDark) style.dark else style.light
}

fun categoryIcon(category: String): ImageVector = builtInCategoryStyle(category)?.icon ?: Lucide.Tag

/**
 * A round badge with the category's icon. [contentDescription] is null where the category name is written next to
 * the badge (every list row and card), so TalkBack does not read it twice.
 */
@Composable
fun CategoryBadge(
    category: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val colors = categoryColors(category)
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(colors.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = categoryIcon(category),
            contentDescription = contentDescription,
            tint = colors.content,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
