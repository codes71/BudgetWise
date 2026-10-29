package app.budgetwise.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.budgetwise.ui.theme.BudgetWiseColors
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.semibold

/** The kit's spinner: a ring in [track] with a quarter arc in [color] going round. Decorative; say "busy" in text. */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    color: Color = BudgetWiseTheme.colors.accent,
    track: Color = BudgetWiseTheme.colors.hairline,
) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    Canvas(modifier.size(size).rotate(turn)) {
        // The kit's SVG: r 9 and stroke 3 in a 24 box.
        val stroke = this.size.minDimension * 3f / 24f
        val radius = this.size.minDimension * 9f / 24f
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        drawCircle(track, radius, style = Stroke(stroke))
        drawArc(color, -90f, 90f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * The primary pill: sage with forest text. Disabled it turns grey; [busy] keeps it disabled (TalkBack says it cannot
 * be pressed again) in the sage container with a spinner, so it reads as working rather than unavailable.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    height: Dp = 56.dp,
    icon: ImageVector? = null,
) {
    val colors = BudgetWiseTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.height(height),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.sageFill,
            contentColor = colors.onSageFill,
            disabledContainerColor = if (busy) colors.sageContainer else colors.raised,
            disabledContentColor = if (busy) colors.onSageContainer else colors.disabled,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        when {
            busy -> {
                Spinner(size = 18.dp)
                Spacer(Modifier.width(10.dp))
            }
            icon != null -> {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(text, style = BudgetWiseTheme.type.size16.semibold)
    }
}

/** The secondary pill: a hairline outline with sage text, 44 dp. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = BudgetWiseTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, colors.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent, disabledContentColor = colors.disabled),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = BudgetWiseTheme.type.size15.semibold)
    }
}

/** A text action in sage: a notice's Undo or Try again, a dialog's buttons. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 40.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = BudgetWiseTheme.colors.accent),
    ) {
        Text(text, style = BudgetWiseTheme.type.size14.semibold)
    }
}

/** The kit's switch, drawn only: the row around it is the toggle (one target, read as a switch). */
@Composable
fun KitSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val knob by animateDpAsState(if (checked) 24.dp else 16.dp, label = "knob")
    val offset by animateDpAsState(if (checked) 24.dp else 6.dp, label = "offset")
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(CircleShape)
            .background(if (checked) colors.sageFill else colors.raised)
            .then(if (checked) Modifier else Modifier.border(2.dp, colors.disabled, CircleShape)),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Off, the 16 dp knob sits 6 dp in from the edge; on, the 24 dp knob sits 4 dp from the far end.
        Box(
            Modifier
                .padding(start = offset)
                .size(knob)
                .clip(CircleShape)
                .background(if (checked) colors.onSageFill else colors.disabled),
        )
    }
}

/** A grey block standing in for content that is loading. No shimmer. */
@Composable
fun SkeletonBar(width: Dp?, height: Dp, modifier: Modifier = Modifier, color: Color = BudgetWiseTheme.colors.raised) {
    Box(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(color),
    )
}

/** A field's error, under it: the alert icon and the message, read out when it appears. */
@Composable
fun FieldError(message: String, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.CircleAlert, contentDescription = null, tint = colors.error, modifier = Modifier.size(16.dp))
        Text(message, style = BudgetWiseTheme.type.size13, color = colors.error)
    }
}

/** A field's label, above it: sage while the field has focus, the error color when it has an error. */
@Composable
fun FieldLabel(text: String, focused: Boolean, error: Boolean, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    Text(
        text = text,
        style = BudgetWiseTheme.type.size13,
        color = when {
            error -> colors.error
            focused -> colors.accent
            else -> colors.muted
        },
        modifier = modifier,
    )
}

/** The field box: the surface with a 1.5 dp hairline, 2 dp sage while focused, 2 dp error color on an error. */
fun Modifier.fieldBox(colors: BudgetWiseColors, focused: Boolean, error: Boolean): Modifier {
    val shape = RoundedCornerShape(14.dp)
    val border = when {
        error -> BorderStroke(2.dp, colors.error)
        focused -> BorderStroke(2.dp, colors.accent)
        else -> BorderStroke(1.5.dp, colors.fieldBorder)
    }
    return clip(shape).background(colors.surface).border(border, shape)
}

/**
 * The kit's text field: [label] above, the box, and [error] under it. [leading] is a muted 20 dp icon; [trailing]
 * sits at the end (the password eye). The field grows to [maxLines] lines.
 */
@Composable
fun KitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    leading: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    placeholder: String? = null,
    error: String? = null,
    hasError: Boolean = error != null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    minHeight: Dp = 56.dp,
    textStyle: TextStyle = BudgetWiseTheme.type.size16,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    below: (@Composable () -> Unit)? = null,
) {
    val colors = BudgetWiseTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label, focused = focused, error = hasError)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.fillMaxWidth(),
            enabled = enabled,
            textStyle = textStyle.copy(color = colors.ink),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else maxLines,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .heightIn(min = minHeight)
                        .fieldBox(colors, focused, hasError)
                        // A trailing button (48 dp) fills the 56 dp box with 4 dp to spare above and below.
                        .padding(
                            start = 16.dp,
                            end = if (trailing != null) 4.dp else 16.dp,
                            top = if (trailing != null) 4.dp else 8.dp,
                            bottom = if (trailing != null) 4.dp else 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (leading != null) Icon(leading, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = textStyle, color = colors.muted, maxLines = 1)
                        }
                        inner()
                    }
                    trailing?.invoke(this)
                }
            },
        )
        if (error != null) FieldError(error)
        below?.invoke()
    }
}

/** The tab look of a two-way switch (the spending view): a 36 dp outlined pill with the chosen tab filled. */
@Composable
fun SegmentedPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(CircleShape)
            .border(1.dp, BudgetWiseTheme.colors.hairline, CircleShape)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
