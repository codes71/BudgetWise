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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.budgetwise.ui.theme.BudgetWiseColors
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked

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
        Text(text, style = BudgetWiseTheme.type.size17.semibold)
    }
}

/**
 * The secondary pill: an outline with [contentColor] text, 48 dp (56 beside a primary button). Sage by default, as for
 * Sync now; the welcome screen's second choice is in ink.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
    contentColor: Color = BudgetWiseTheme.colors.accent,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = CircleShape,
        border = BorderStroke(1.dp, colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor, disabledContentColor = colors.disabled),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        // Ink text is the quiet choice next to a primary button; sage text is an action on its own.
        Text(text, style = if (contentColor == colors.accent) type.size15.semibold else type.size16)
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
        Text(text, style = BudgetWiseTheme.type.size15.bold)
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

/** A field's error, under it, read out when it appears. [action] is an optional link after it (Sign in instead). */
@Composable
fun FieldError(message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val colors = BudgetWiseTheme.colors
    Row(
        modifier = modifier.semantics(mergeDescendants = action == null) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(message, style = BudgetWiseTheme.type.size13, color = colors.error, modifier = Modifier.weight(1f, fill = false))
        action?.invoke()
    }
}

/**
 * The kit's small label over a section or a field: 12 semibold muted, uppercase and widely tracked in English. Burmese
 * has no letter case and tracking breaks it, so there it is the same text in bold.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = BudgetWiseTheme.colors.muted) {
    val type = BudgetWiseTheme.type
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text = if (type.burmese) text else text.uppercase(locale),
        style = if (type.burmese) type.size12.bold else type.size12.semibold.tracked(0.08f),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { contentDescription = text },
    )
}

/** A field's label, above it. It stays muted; the field's outline shows focus and errors. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) = SectionLabel(text, modifier)

/** The field box: the surface with no outline at rest, 1 dp sage while focused, 1 dp error color on an error. */
fun Modifier.fieldBox(colors: BudgetWiseColors, focused: Boolean, error: Boolean, shape: Shape = RoundedCornerShape(16.dp)): Modifier {
    val base = clip(shape).background(colors.surface)
    return when {
        error -> base.border(1.dp, colors.error, shape)
        focused -> base.border(1.dp, colors.accent, shape)
        else -> base
    }
}

/**
 * The kit's text field: [label] above, the box, and [error] under it. [leading] is a muted 18 dp icon; [trailing]
 * sits at the end (the password eye). The field grows to [maxLines] lines. [below] shows under the box when there is
 * no error (a hint or a counter).
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
    errorAction: (@Composable () -> Unit)? = null,
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(label)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = fieldModifier.fillMaxWidth(),
                enabled = enabled,
                textStyle = textStyle.forContent(value).copy(color = colors.ink),
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
                                start = 18.dp,
                                end = if (trailing != null) 4.dp else 18.dp,
                                top = if (trailing != null) 4.dp else 8.dp,
                                bottom = if (trailing != null) 4.dp else 8.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (leading != null) Icon(leading, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
                        Box(Modifier.weight(1f)) {
                            if (value.isEmpty() && placeholder != null) {
                                Text(placeholder, style = textStyle.forContent(placeholder), color = colors.muted, maxLines = 1)
                            }
                            inner()
                        }
                        trailing?.invoke(this)
                    }
                },
            )
            if (error != null) FieldError(error, action = errorAction) else below?.invoke()
        }
    }
}

/** A hint under a field (At least 6 characters): muted 13. */
@Composable
fun FieldHint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = BudgetWiseTheme.type.size13, color = BudgetWiseTheme.colors.muted, modifier = modifier)
}

/** The short sage rule under a big title or amount: the redesign's signature mark. Decorative. */
@Composable
fun SageRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 48.dp, height = 2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(BudgetWiseTheme.colors.accent),
    )
}

/** The look of a two-way switch (the spending view, expense or income): a filled track with the chosen tab filled. */
@Composable
fun SegmentedPill(modifier: Modifier = Modifier, padding: Dp = 3.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(BudgetWiseTheme.colors.segmentTrack)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
