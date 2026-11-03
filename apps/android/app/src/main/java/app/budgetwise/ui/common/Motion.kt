package app.budgetwise.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics

/** Draft 3's restrained motion. The Compose animation clock handles the system duration scale. */
object BudgetMotion {
    const val BACKGROUND_ROTATION_MS = 5_000L
    private const val FAST_MS = 150
    private const val CHANGE_MS = 220
    private const val RESPONSE_MS = 80
    private const val ROTATION_MS = 900

    fun <T> effects(): FiniteAnimationSpec<T> = tween(FAST_MS, easing = FastOutSlowInEasing)
    fun <T> change(): FiniteAnimationSpec<T> = tween(CHANGE_MS, easing = FastOutSlowInEasing)
    fun <T> collapse(): FiniteAnimationSpec<T> = tween(FAST_MS, easing = FastOutSlowInEasing)
    fun <T> response(): FiniteAnimationSpec<T> = tween(RESPONSE_MS, easing = LinearEasing)
    fun rotation() = infiniteRepeatable(tween<Float>(ROTATION_MS, easing = LinearEasing), RepeatMode.Restart)
}

/** Local state replacement; [content] always receives the actual outgoing/incoming target. */
@Composable
fun <S> MotionContent(
    targetState: S,
    modifier: Modifier = Modifier,
    label: String = "content",
    contentKey: (S) -> Any? = { it },
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        contentKey = contentKey,
        transitionSpec = {
            (fadeIn(BudgetMotion.effects()) togetherWith fadeOut(BudgetMotion.effects()))
                .using(SizeTransform(clip = false, sizeAnimationSpec = { initial, target ->
                    // Removing inline feedback releases its slot with the other fast structural reveals.
                    // Nonempty panel replacements keep the content-change tier in either direction.
                    if (initial.height > 0 && target.height == 0) BudgetMotion.collapse() else BudgetMotion.change()
                }))
        },
        label = label,
    ) { shown ->
        val scope = this
        val active = contentKey(shown) == contentKey(targetState)
        // The old picture may fade, but its controls cannot edit the incoming state or take focus.
        Box(Modifier.motionActive(active)) { content(scope, shown) }
    }
}

/** A structural reveal, including neighboring layout, with a complete resting state at scale zero. */
@Composable
fun MotionReveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(BudgetMotion.effects()) + expandVertically(BudgetMotion.change()),
        exit = fadeOut(BudgetMotion.effects()) + shrinkVertically(BudgetMotion.collapse()),
        label = "reveal",
    ) {
        val scope = this
        Box(Modifier.motionActive(visible)) { content(scope) }
    }
}

/** Exiting content stays drawable without intercepting input intended for the current form. */
private fun Modifier.motionActive(active: Boolean): Modifier = if (active) this else this
    .semantics { hideFromAccessibility() }
    .focusProperties { canFocus = false }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
