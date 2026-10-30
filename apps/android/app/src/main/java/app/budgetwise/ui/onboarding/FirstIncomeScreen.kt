package app.budgetwise.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.ui.common.FieldLabel
import app.budgetwise.ui.common.KitTextField
import app.budgetwise.ui.common.KyatGroupingTransformation
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.Notice
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.common.toMyanmarDigits
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import java.time.LocalDate
import java.time.YearMonth

/** [onDone] goes on to the app: after the income is saved, or straight away on Skip. */
@Composable
fun FirstIncomeRoute(onDone: () -> Unit, viewModel: FirstIncomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it == FirstIncomeEvent.Saved) done() }
    }
    val description = stringResource(state.source.label)
    FirstIncomeScreen(
        state = state,
        onAmountChange = viewModel::onAmountChange,
        onSourceChange = viewModel::onSourceChange,
        onSave = { viewModel.save(description) },
        onSkip = onDone,
    )
}

/**
 * Step 2 of 2, with no way back (the account exists): the amount, where it comes from, and what the month will show.
 * Skip goes on without saving anything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstIncomeScreen(
    state: FirstIncomeUiState,
    onAmountChange: (String) -> Unit,
    onSourceChange: (IncomeSource) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val keyboard = LocalSoftwareKeyboardController.current
    val editable = !state.saving
    AuthPage(
        title = stringResource(R.string.income_title),
        subtitle = stringResource(R.string.income_subtitle),
        onBack = null,
        modifier = modifier,
        step = Step(at = 2, of = 2),
        button = {
            PrimaryButton(
                text = stringResource(R.string.income_start),
                onClick = {
                    keyboard?.hide()
                    onSave()
                },
                enabled = state.canSave,
                busy = state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        footer = { SkipLink(onSkip, enabled = editable) },
    ) {
        val transformation = remember(state.myanmarDigits) { KyatGroupingTransformation(state.myanmarDigits) }
        KitTextField(
            value = state.amountDigits,
            onValueChange = onAmountChange,
            label = stringResource(R.string.manual_amount),
            placeholder = if (state.myanmarDigits) toMyanmarDigits("0") else "0",
            enabled = editable,
            minHeight = 84.dp,
            textStyle = type.size44.extraBold.tracked(-0.035f),
            trailing = { Text("Ks", style = type.size22.medium, color = colors.muted, modifier = Modifier.padding(end = 18.dp)) },
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel(stringResource(R.string.income_from))
            FlowRow(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IncomeSource.entries.forEach { source ->
                    SourceChip(stringResource(source.label), selected = state.source == source, enabled = editable) {
                        onSourceChange(source)
                    }
                }
            }
        }
        state.amount?.let { MonthPreview(it, state.today, state.myanmarDigits) }
        if (state.saveFailed) {
            Notice(
                tone = NoticeTone.ERROR,
                icon = Lucide.CircleAlert,
                title = stringResource(R.string.manual_save_failed),
                detail = stringResource(R.string.login_error_generic),
            )
        }
    }
}

/** A pill per source; the chosen one fills sage with a check. One 48 dp target each. */
@Composable
private fun SourceChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(colors.sageFill) else Modifier.border(1.dp, colors.outline, CircleShape))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(start = if (selected) 12.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) Icon(Lucide.CheckBold, contentDescription = null, tint = colors.onSageFill, modifier = Modifier.size(16.dp))
        Text(
            text = text,
            style = if (selected) type.size15.semibold else type.size15.medium,
            color = if (selected) colors.onSageFill else colors.ink,
            maxLines = 1,
        )
    }
}

/** What the month will start from, over a hairline: "850,000 Ks left in October", the amount in sage. */
@Composable
private fun MonthPreview(amount: Long, today: LocalDate, myanmarDigits: Boolean) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val figure = groupDigits(amount, myanmarDigits)
    val line = stringResource(R.string.income_preview, figure, monthName(YearMonth.from(today)))
    Column {
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        Text(
            text = buildAnnotatedString {
                append(line)
                // The amount with its "Ks" when the sentence writes them together, as both languages do.
                val amount = "$figure Ks".takeIf { it in line } ?: figure
                val at = line.indexOf(amount)
                if (at >= 0) addStyle(SpanStyle(color = colors.accent), at, at + amount.length)
            },
            style = type.size16.semibold,
            color = colors.ink,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(stringResource(R.string.income_note), style = type.size13, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Skip for now: muted, one 48 dp target, where the other forms have their footer link. */
@Composable
private fun SkipLink(onClick: () -> Unit, enabled: Boolean) {
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.income_skip), style = BudgetWiseTheme.type.size15.semibold, color = BudgetWiseTheme.colors.muted)
    }
}

@Preview(showBackground = true)
@Composable
private fun FirstIncomeScreenPreview() {
    BudgetWiseTheme {
        FirstIncomeScreen(
            state = FirstIncomeUiState(today = LocalDate.parse("2026-10-30"), amountDigits = "850000"),
            onAmountChange = {},
            onSourceChange = {},
            onSave = {},
            onSkip = {},
        )
    }
}
