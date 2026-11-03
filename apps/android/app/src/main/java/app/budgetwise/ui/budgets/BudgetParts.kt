package app.budgetwise.ui.budgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.semibold

/** A thin rounded progress bar on the raised track; [progress] above 1 fills it. */
@Composable
internal fun Bar(progress: Float, color: Color, height: Dp, modifier: Modifier = Modifier) {
    Box(Modifier.fillMaxWidth().then(modifier).height(height).clip(CircleShape).background(BudgetWiseTheme.colors.raised)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }
}

/**
 * A round badge with a dashed outline: Everything else (no category of its own) and Add a category (not there yet).
 * Decorative; the row's text says what it is.
 */
@Composable
internal fun DashedBadge(icon: ImageVector, tint: Color, outline: Color, modifier: Modifier = Modifier, size: Dp = 38.dp) {
    Box(
        modifier
            .size(size)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawCircle(
                    color = outline,
                    radius = (this.size.minDimension - stroke) / 2,
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Everything else's badge: the wallet in a dashed circle. */
@Composable
internal fun EverythingElseBadge(modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    DashedBadge(Lucide.Wallet, tint = colors.muted, outline = colors.outline, modifier = modifier)
}

/** The board's checkbox, drawn only: the row around it is the toggle. Ticked it fills sage with a forest check. */
@Composable
internal fun KitCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .size(22.dp)
            .clip(shape)
            .then(if (checked) Modifier.background(colors.sageFill) else Modifier.border(2.dp, colors.outline, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Lucide.CheckBold, contentDescription = null, tint = colors.onSageFill, modifier = Modifier.size(14.dp))
    }
}

/** A plan screen's "Step 1 of 2", at the header's end. */
@Composable
internal fun StepText(text: String) {
    Text(text, style = BudgetWiseTheme.type.size13.semibold, color = BudgetWiseTheme.colors.muted, maxLines = 1)
}
