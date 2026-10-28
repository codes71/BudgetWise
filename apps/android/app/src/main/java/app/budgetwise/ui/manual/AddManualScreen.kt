package app.budgetwise.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.FieldError
import app.budgetwise.ui.common.FieldLabel
import app.budgetwise.ui.common.KitTextField
import app.budgetwise.ui.common.KyatGroupingTransformation
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.Notice
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.common.categoryColors
import app.budgetwise.ui.common.categoryIcon
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.dayLabel
import app.budgetwise.ui.common.fieldBox
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.localizedDate
import app.budgetwise.ui.common.toMyanmarDigits
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tabular
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.WeekFields

/** [onDone] closes the form: after a save, or when the user closes it. */
@Composable
fun AddManualRoute(onDone: () -> Unit, viewModel: AddManualViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it == AddManualEvent.Saved) done() }
    }
    AddManualScreen(
        state = state,
        onClose = onDone,
        onTypeChange = viewModel::onTypeChange,
        onAmountChange = viewModel::onAmountChange,
        onCategoryChange = viewModel::onCategoryChange,
        onDescriptionChange = viewModel::onDescriptionChange,
        onDateChange = viewModel::onDateChange,
        onSave = viewModel::save,
    )
}

@Composable
fun AddManualScreen(
    state: AddManualUiState,
    onClose: () -> Unit,
    onTypeChange: (TxType) -> Unit,
    onAmountChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    focusAmount: Boolean = true,
) {
    val colors = BudgetWiseTheme.colors
    val amountFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    // The amount comes first: the keyboard is up for it as soon as the form opens (once, not again on rotation).
    var focused by rememberSaveable { mutableStateOf(!focusAmount) }
    LaunchedEffect(Unit) {
        if (!focused) {
            amountFocus.requestFocus()
            focused = true
        }
    }

    // Save stays pinned at the bottom, above the keyboard when it is open, so nothing is pushed off screen.
    Column(
        modifier
            .fillMaxSize()
            .background(colors.ground)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        BackHeader(
            title = stringResource(R.string.manual_title),
            onBack = onClose,
            icon = Lucide.X,
            backLabel = stringResource(R.string.action_close),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // While saving the form is dimmed and locked; the button says it is busy.
            val editable = !state.saving
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .alpha(if (state.saving) 0.5f else 1f)
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                TypeSelector(state.type, editable, onTypeChange)
                AmountField(state, editable, onAmountChange, amountFocus, onNext = { descriptionFocus.requestFocus() })
                // Income always goes under "Income" (AddManualUiState.savedCategory), so only an expense asks.
                if (state.type != TxType.INCOME) CategoryPicker(state, editable, onCategoryChange)
                DescriptionField(state, editable, onDescriptionChange, descriptionFocus, onDone = { keyboard?.hide() })
                DateField(state, editable, onClick = {
                    keyboard?.hide()
                    pickingDate = true
                })
            }
            if (state.saveFailed) {
                Notice(
                    tone = NoticeTone.ERROR,
                    icon = Lucide.CircleAlert,
                    title = stringResource(R.string.manual_save_failed),
                    detail = stringResource(R.string.manual_save_failed_detail),
                    floating = true,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }
        Column(Modifier.fillMaxWidth().background(colors.ground)) {
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            PrimaryButton(
                text = stringResource(if (state.saving) R.string.manual_saving else R.string.manual_save),
                onClick = onSave,
                enabled = state.canSave,
                busy = state.saving,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            )
        }
    }

    if (pickingDate) {
        DateSheet(
            date = state.date,
            today = state.today,
            myanmarDigits = state.myanmarDigits,
            onPick = {
                onDateChange(it)
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }
}

/** Expense or income, as two halves of one pill. Expense selects in coral, income in the Income badge's green. */
@Composable
private fun TypeSelector(type: TxType, enabled: Boolean, onTypeChange: (TxType) -> Unit) {
    val colors = BudgetWiseTheme.colors
    val income = categoryColors(CategoryRepository.INCOME)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(if (colors.isDark) Color.Transparent else colors.surface)
            .border(1.dp, colors.chipBorder, CircleShape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        listOf(
            Triple(TxType.EXPENSE, R.string.manual_expense, Lucide.ArrowUpRightBold),
            Triple(TxType.INCOME, R.string.manual_income, Lucide.ArrowDownLeftBold),
        ).forEach { (option, label, icon) ->
            val selected = option == type
            val (container, content) = when {
                !selected -> Color.Transparent to colors.muted
                option == TxType.EXPENSE -> colors.expenseSelected to colors.onExpenseSelected
                else -> income.container to income.content
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(container)
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onTypeChange(option) }),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(label),
                    style = if (selected) BudgetWiseTheme.type.size15.semibold else BudgetWiseTheme.type.size15.medium,
                    color = content,
                )
            }
        }
    }
}

@Composable
private fun AmountField(
    state: AddManualUiState,
    enabled: Boolean,
    onAmountChange: (String) -> Unit,
    focus: FocusRequester,
    onNext: () -> Unit,
) {
    val type = BudgetWiseTheme.type
    val big = type.size32.bold.tabular
    val transformation = remember(state.myanmarDigits) { KyatGroupingTransformation(state.myanmarDigits) }
    KitTextField(
        value = state.amountDigits,
        onValueChange = onAmountChange,
        label = stringResource(R.string.manual_amount),
        fieldModifier = Modifier.focusRequester(focus),
        placeholder = if (state.myanmarDigits) toMyanmarDigits("0") else "0",
        error = state.amountError?.let { error ->
            stringResource(
                when (error) {
                    AmountError.MISSING -> R.string.manual_amount_error_missing
                    AmountError.ZERO -> R.string.manual_amount_error_zero
                },
            )
        },
        enabled = enabled,
        minHeight = 76.dp,
        textStyle = big,
        trailing = {
            Text("Ks", style = type.size20.medium, color = BudgetWiseTheme.colors.muted, modifier = Modifier.padding(end = 14.dp))
        },
        visualTransformation = transformation,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        below = if (state.type == TxType.INCOME) {
            {
                Text(
                    stringResource(R.string.manual_income_note),
                    style = type.size13,
                    color = BudgetWiseTheme.colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        } else {
            null
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(state: AddManualUiState, enabled: Boolean, onCategoryChange: (String) -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val error = state.categoryError != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(stringResource(R.string.manual_category), focused = false, error = error)
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.categories.forEach { name ->
                val selected = name == state.category
                val label = categoryLabel(name)
                Row(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.sageContainer else Color.Transparent)
                        .border(1.dp, if (selected) colors.accent else colors.chipBorder, CircleShape)
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onCategoryChange(name) })
                        .padding(start = 12.dp, end = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (selected) Lucide.CheckBold else categoryIcon(name),
                        contentDescription = null,
                        tint = if (selected) colors.onSageContainer else categoryColors(name).content,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        label,
                        style = type.size14.medium.forContent(label),
                        color = if (selected) colors.onSageContainer else colors.ink,
                    )
                }
            }
        }
        if (error) FieldError(stringResource(R.string.manual_category_error))
    }
}

@Composable
private fun DescriptionField(
    state: AddManualUiState,
    enabled: Boolean,
    onDescriptionChange: (String) -> Unit,
    focus: FocusRequester,
    onDone: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val max = AddManualViewModel.MAX_DESCRIPTION
    val error = when (state.descriptionError) {
        DescriptionError.BLANK -> stringResource(R.string.manual_description_error_blank)
        DescriptionError.TOO_LONG -> pluralStringResource(R.plurals.manual_description_error_too_long, max, max)
        null -> null
    }
    KitTextField(
        value = state.description,
        onValueChange = onDescriptionChange,
        label = stringResource(R.string.manual_description),
        fieldModifier = Modifier.focusRequester(focus),
        leading = Lucide.Lines,
        placeholder = stringResource(R.string.manual_description_hint),
        hasError = error != null,
        enabled = enabled,
        // Burmese descriptions wrap onto a few lines instead of scrolling sideways.
        singleLine = false,
        maxLines = 3,
        textStyle = BudgetWiseTheme.type.size16.forContent(state.description),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        below = {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error != null) FieldError(error, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                // Formatted like amounts: the Myanmar-digits setting decides, not the phone's locale.
                Text(
                    stringResource(
                        R.string.manual_description_count,
                        groupDigits(state.descriptionLength.toLong(), state.myanmarDigits),
                        groupDigits(max.toLong(), state.myanmarDigits),
                    ),
                    style = if (error != null) BudgetWiseTheme.type.size12.semibold.tabular else BudgetWiseTheme.type.size12.tabular,
                    color = if (state.descriptionError == DescriptionError.TOO_LONG) colors.error else colors.muted,
                )
            }
        },
    )
}

@Composable
private fun DateField(state: AddManualUiState, enabled: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val error = state.dateError != null
    val changeLabel = stringResource(R.string.manual_date_change)
    val dateText = if (state.date == state.today || state.date == state.today.minusDays(1)) {
        "${dayLabel(state.date, state.today)}, ${localizedDate(state.date, "dMMMM", state.myanmarDigits)}"
    } else {
        localizedDate(state.date, if (state.date.year == state.today.year) "EEEEdMMMM" else "EEEEdMMMMy", state.myanmarDigits)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Drawn like the text fields above, but it opens the date sheet. TalkBack hears "double tap to change date".
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .fieldBox(colors, focused = false, error = error)
                .clickable(enabled = enabled, onClickLabel = changeLabel, role = Role.Button, onClick = onClick)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Lucide.Calendar, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.manual_date), style = type.size12, color = if (error) colors.error else colors.muted)
                Text(dateText, style = type.size16, color = colors.ink)
            }
            Icon(Lucide.ChevronDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
        }
        if (error) FieldError(stringResource(R.string.manual_date_error_future))
    }
}

/**
 * The board's date sheet: a month at a time, today and earlier only (the form refuses future dates). The day tapped is
 * kept until OK; Cancel or a swipe down leaves the date as it was. Days follow the Myanmar-digits setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSheet(date: LocalDate, today: LocalDate, myanmarDigits: Boolean, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val locale = LocalConfiguration.current.locales[0]
    val thisMonth = YearMonth.from(today)
    var shown by rememberSaveable { mutableStateOf(YearMonth.from(date.coerceAtMost(today))) }
    var picked by rememberSaveable { mutableStateOf(date.coerceAtMost(today)) }
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.raised,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp).size(width = 32.dp, height = 4.dp).clip(CircleShape).background(colors.disabled))
        },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Text(
                stringResource(R.string.manual_date),
                style = type.size20.semibold,
                color = colors.ink,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
            )
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shown = shown.minusMonths(1) }, modifier = Modifier.size(48.dp)) {
                    Icon(Lucide.ChevronLeft, stringResource(R.string.manual_previous_month), tint = colors.ink, modifier = Modifier.size(22.dp))
                }
                Text(
                    localizedDate(shown, "MMMMy", myanmarDigits),
                    style = type.size16.semibold,
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                val canGoOn = shown < thisMonth
                IconButton(onClick = { shown = shown.plusMonths(1) }, enabled = canGoOn, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Lucide.ChevronRight,
                        stringResource(R.string.manual_next_month),
                        tint = if (canGoOn) colors.ink else colors.unavailable,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            val weekdays = remember(locale, firstDay) { (0 until 7).map { firstDay.plus(it.toLong()).getDisplayName(DateTextStyle.NARROW, locale) } }
            Row(Modifier.fillMaxWidth().height(32.dp).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                weekdays.forEach { Text(it, style = type.size13, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            }
            val lead = (shown.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
            val cells = List(lead) { null } + (1..shown.lengthOfMonth()).map { shown.atDay(it) }
            val todayLabel = stringResource(R.string.manual_day_today)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        (0 until 7).forEach { i ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                val day = week.getOrNull(i) ?: return@Box
                                val future = day.isAfter(today)
                                val selected = day == picked
                                val name = localizedDate(day, "MMMMd")
                                val spoken = if (day == today) "$name, $todayLabel" else name
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) colors.sageFill else Color.Transparent)
                                        .selectable(selected = selected, enabled = !future, role = Role.RadioButton, onClick = { picked = day })
                                        .semantics { contentDescription = spoken },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        groupDigits(day.dayOfMonth.toLong(), myanmarDigits),
                                        style = if (selected) type.size15.semibold.tabular else type.size15.tabular,
                                        color = when {
                                            selected -> colors.onSageFill
                                            future -> colors.unavailable
                                            else -> colors.ink
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextAction(stringResource(R.string.action_cancel), onClick = onDismiss)
                TextAction(stringResource(R.string.action_ok), onClick = { onPick(picked) })
            }
        }
    }
}

private fun LocalDate.coerceAtMost(max: LocalDate): LocalDate = if (isAfter(max)) max else this

@Preview(showBackground = true)
@Composable
private fun AddManualScreenPreview() {
    val today = LocalDate.parse("2026-10-23")
    BudgetWiseTheme {
        AddManualScreen(
            state = AddManualUiState(
                today = today,
                amountDigits = "4500",
                category = "Food and Drink Item",
                description = "Shan noodles for lunch",
            ),
            onClose = {},
            onTypeChange = {},
            onAmountChange = {},
            onCategoryChange = {},
            onDescriptionChange = {},
            onDateChange = {},
            onSave = {},
            focusAmount = false,
        )
    }
}
