package app.budgetwise.ui.budgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.FieldError
import app.budgetwise.ui.common.KyatGroupingTransformation
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.SkeletonBar
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.common.toMyanmarDigits
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import java.time.YearMonth
import kotlin.math.abs

/** [onClose] leaves without saving; [onChecked] after the check is saved, with the month to plan next. */
@Composable
fun MonthEndRoute(onClose: () -> Unit, onChecked: (YearMonth) -> Unit, viewModel: MonthEndViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val checked by rememberUpdatedState(onChecked)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MonthEndEvent.Checked -> checked(event.next)
            }
        }
    }
    val notRecorded = stringResource(R.string.month_end_not_recorded)
    MonthEndScreen(
        state = state,
        onClose = onClose,
        onAnswer = viewModel::onAnswer,
        onOnHandChange = viewModel::onOnHandChange,
        onAddGap = viewModel::onAddGap,
        onNext = { viewModel.next(notRecorded) },
        onRetry = viewModel::retryPull,
    )
}

/**
 * The end of a month: what is left of its budget, and whether about that much is really on hand. A smaller amount
 * offers to record the difference, so the month's numbers match; Next saves the check and goes on to plan the next
 * month.
 */
@Composable
fun MonthEndScreen(
    state: MonthEndUiState,
    onClose: () -> Unit,
    onAnswer: (OnHand) -> Unit,
    onOnHandChange: (String) -> Unit,
    onAddGap: (Boolean) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val colors = BudgetWiseTheme.colors
    val name = monthName(state.month)
    Column(
        modifier
            .fillMaxSize()
            .background(colors.ground)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        BackHeader(
            title = stringResource(R.string.month_end_title, name),
            onBack = onClose,
            icon = Lucide.X,
            backLabel = stringResource(R.string.action_close),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 16.dp),
        ) {
            when {
                state.counted -> {
                    Leftover(state)
                    Question(state, onAnswer, onOnHandChange, Modifier.padding(top = 40.dp))
                    Gap(state, onAddGap, Modifier.padding(top = 22.dp))
                }
                state.plan != null -> Counting(state, onRetry)
                state.loaded -> Text(
                    stringResource(R.string.month_end_error_no_plan, name),
                    style = BudgetWiseTheme.type.size16,
                    color = colors.muted,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().background(colors.ground).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(
                text = stringResource(R.string.month_end_next, monthName(state.nextMonth)),
                onClick = onNext,
                enabled = state.canGoOn,
                busy = state.saving,
                trailingIcon = Lucide.ArrowRightBold,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { FieldError(errorText(it, name), Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun errorText(error: EndCheckError, month: String): String = when (error) {
    EndCheckError.NETWORK -> stringResource(R.string.plan_save_failed_network)
    EndCheckError.NOT_OVER -> stringResource(R.string.month_end_error_not_over, month)
    EndCheckError.NO_PLAN -> stringResource(R.string.month_end_error_no_plan, month)
    EndCheckError.SIGN_IN -> stringResource(R.string.plan_save_failed_sign_in)
    EndCheckError.ENTRY_FAILED -> stringResource(R.string.month_end_error_entry)
    EndCheckError.ACCOUNT_CHANGED -> stringResource(R.string.month_end_error_account)
    EndCheckError.OTHER -> stringResource(R.string.plan_save_failed)
}

/**
 * Before the ledger pull went through: the leftover's place as grey blocks while it runs, else why it failed and Try
 * again (or, with the session gone, that signing in is needed).
 */
@Composable
private fun Counting(state: MonthEndUiState, onRetry: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val name = monthName(state.month)
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.month_end_left_in, name))
        when (state.ledger) {
            LedgerPull.FAILED -> FieldError(
                stringResource(R.string.month_end_pull_failed),
                Modifier.padding(top = 12.dp),
                action = { TextAction(stringResource(R.string.action_try_again), onClick = onRetry) },
            )
            LedgerPull.SIGN_IN -> FieldError(stringResource(R.string.month_end_pull_sign_in, name), Modifier.padding(top = 12.dp))
            else -> {
                val counting = stringResource(R.string.month_end_counting)
                Column(Modifier.clearAndSetSemantics { contentDescription = counting }) {
                    SkeletonBar(220.dp, 56.dp, Modifier.padding(top = 10.dp), color = colors.surface)
                    SkeletonBar(200.dp, 14.dp, Modifier.padding(top = 12.dp), color = colors.surface)
                }
            }
        }
    }
}

/** What is left in the month's budget, big in sage (coral when the month went over), and what was spent of it. */
@Composable
private fun Leftover(state: MonthEndUiState) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = monthName(state.month)
    val over = state.leftKyat < 0
    val label = stringResource(if (over) R.string.month_end_over_in else R.string.month_end_left_in, name)
    val figure = groupDigits(abs(state.leftKyat), state.myanmarDigits)
    val spoken = "$label ${stringResource(R.string.kyat_amount, groupDigits(abs(state.leftKyat)))}, " +
        stringResource(R.string.budgets_spent_of_limit_spoken, groupDigits(state.spentKyat), groupDigits(state.totalKyat))
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        SectionLabel(label)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                figure,
                style = (if (figure.length > 8) type.size44 else type.size60).extraBold.tracked(-0.035f),
                color = if (over) colors.coralText else colors.accent,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            Text("Ks", style = type.size18.medium, color = colors.muted, modifier = Modifier.alignByBaseline())
        }
        Text(
            stringResource(R.string.budgets_spent_of_total, groupDigits(state.spentKyat, state.myanmarDigits), groupDigits(state.totalKyat, state.myanmarDigits)),
            style = type.size14,
            color = colors.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** The question, Yes / No as two radio cards, and "I have" for No (with the keyboard up for it). */
@Composable
private fun Question(state: MonthEndUiState, onAnswer: (OnHand) -> Unit, onOnHandChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.month_end_question),
            style = type.size22.bold.tracked(-0.01f),
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.month_end_hint), style = type.size14, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.padding(top = 20.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice(
                text = stringResource(R.string.month_end_yes, groupDigits(state.expectedKyat, state.myanmarDigits)),
                selected = state.answer == OnHand.YES,
                enabled = !state.saving,
                onClick = { onAnswer(OnHand.YES) },
            )
            Choice(
                text = stringResource(R.string.month_end_no),
                selected = state.answer == OnHand.NO,
                enabled = !state.saving,
                onClick = { onAnswer(OnHand.NO) },
            )
        }
        if (state.answer == OnHand.NO) OnHandField(state, onOnHandChange, Modifier.padding(top = 10.dp))
    }
}

/** A radio card: the ring and the answer; selected it fills with a sage tint inside a sage outline. */
@Composable
private fun Choice(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else colors.ground)
            .border(1.dp, if (selected) colors.accent else colors.hairline, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) colors.accent else colors.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(colors.accent))
        }
        Text(text, style = if (selected) type.size16.semibold else type.size16.medium, color = colors.ink, modifier = Modifier.weight(1f))
    }
}

/** "I have … Ks": the amount on hand, right aligned in a sage-outlined box. */
@Composable
private fun OnHandField(state: MonthEndUiState, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val label = stringResource(R.string.month_end_i_have)
    val transformation = remember(state.myanmarDigits) { KyatGroupingTransformation(state.myanmarDigits) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(shape)
            .background(colors.surface)
            .border(2.dp, colors.accent, shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = type.size15.medium, color = colors.muted)
        BasicTextField(
            value = state.onHandDigits,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = label },
            enabled = !state.saving,
            textStyle = type.size22.bold.copy(color = colors.ink, textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            singleLine = true,
            visualTransformation = transformation,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (state.onHandDigits.isEmpty()) {
                        Text(if (state.myanmarDigits) toMyanmarDigits("0") else "0", style = type.size22.bold, color = colors.muted)
                    }
                    inner()
                }
            },
        )
        Text("Ks", style = type.size15.medium, color = colors.muted)
    }
}

/**
 * Less on hand than expected: how much went unrecorded, and a ticked box to add it to the month as an entry. More on
 * hand: a plain line, and nothing is added.
 */
@Composable
private fun Gap(state: MonthEndUiState, onAddGap: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val gap = state.gapKyat
    if (state.onHandKyat == null || gap == 0L) return
    val name = monthName(state.month)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (gap < 0) {
            Text(stringResource(R.string.month_end_more, formatKyat(-gap, state.myanmarDigits)), style = type.size15, color = colors.ink)
            return@Column
        }
        val amount = formatKyat(gap, state.myanmarDigits)
        val line = stringResource(R.string.month_end_gap, amount)
        Text(
            buildAnnotatedString {
                append(line)
                val at = line.indexOf(amount)
                if (at >= 0) addStyle(SpanStyle(color = colors.coralText, fontWeight = FontWeight.Bold), at, at + amount.length)
            },
            style = type.size15,
            color = colors.ink,
            modifier = Modifier.semantics { contentDescription = line },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .toggleable(value = state.addGap, enabled = !state.saving, role = Role.Checkbox, onValueChange = onAddGap)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KitCheckbox(state.addGap)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.month_end_add_gap, name, stringResource(R.string.month_end_not_recorded)),
                    style = type.size15.medium,
                    color = colors.ink,
                )
                Text(stringResource(R.string.month_end_add_gap_detail, name), style = type.size13, color = colors.muted)
            }
        }
    }
}
