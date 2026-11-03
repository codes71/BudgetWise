package app.budgetwise.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked

/** Where a screen is in first-run setup: [at] of [of]. */
internal data class Step(val at: Int, val of: Int)

/**
 * Sign in and onboarding: a compact bar, optional progress and eyebrow, then the display title and fields.
 * [button] stays above the keyboard; the scroll area keeps every field reachable at larger font sizes. [footer] (the link to the other form, or Skip) shows only while the keyboard is down, so it
 * never sits where the next field was.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AuthPage(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    button: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    step: Step? = null,
    barTitle: String? = null,
    eyebrow: String? = null,
    fields: @Composable ColumnScope.() -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(
        modifier
            .fillMaxSize()
            .background(colors.ground)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        if (onBack != null) {
            BackHeader(title = barTitle, onBack = onBack)
        } else {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (barTitle != null) Text(barTitle, style = type.size24.bold, color = colors.ink)
            }
        }
        if (step != null) StepIndicator(step)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        ) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                if (eyebrow != null) {
                    Text(eyebrow, style = type.size12.semibold, color = colors.muted, modifier = Modifier.padding(bottom = 12.dp))
                }
                Text(
                    text = title,
                    style = type.size32.bold.tracked(-0.035f),
                    color = colors.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(subtitle, style = type.size16, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
                Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = fields)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            button()
            if (!WindowInsets.isImeVisible) footer() else Spacer(Modifier.height(12.dp))
        }
    }
}

/** Compact step count and a continuous progress line, announced as one localized step label. */
@Composable
private fun StepIndicator(step: Step) {
    val colors = BudgetWiseTheme.colors
    val label = stringResource(R.string.onboarding_step, step.at, step.of)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp).clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.draft3_step_count, step.at, step.of), style = BudgetWiseTheme.type.size13.medium, color = colors.muted)
        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.hairline)) {
            Box(Modifier.fillMaxWidth(step.at.toFloat() / step.of).height(4.dp).background(colors.accent))
        }
    }
}

/** "Already have an account? Sign in": muted words and a sage link, one 48 dp target. */
@Composable
internal fun AuthFooterLink(prompt: String, link: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            buildAnnotatedString {
                append(prompt)
                append(" ")
                val start = length
                append(link)
                addStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.SemiBold), start, length)
            },
            style = type.size14,
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
    }
}
