package app.budgetwise.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.text.style.TextOverflow
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
import app.budgetwise.ui.common.SecondaryButton
import app.budgetwise.ui.common.SegmentedPill
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
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.tracked
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.voice.rememberSayIt
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.WeekFields

/**
 * [onDone] closes the form: after a save, or when the user closes it. [onOpenVoice] opens voice entry, [onOpenScan]
 * receipt scanning.
 */
@Composable
fun AddManualRoute(
    onDone: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenScan: () -> Unit,
    viewModel: AddManualViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sayIt = rememberSayIt(onOpenVoice)
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
        onSayIt = sayIt,
        onScan = onOpenScan,
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
    /** Null on a phone without the memory for voice: no Say it, and Scan takes the whole row. */
    onSayIt: (() -> Unit)?,
    onScan: () -> Unit,
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
                    .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Scan and Say it sit with the type: all three choose how the entry is made, before the fields.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TypeSelector(state.type, editable, onTypeChange)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton(
                            text = stringResource(if (onSayIt != null) R.string.scan_short else R.string.scan_entry),
                            onClick = {
                                keyboard?.hide()
                                onScan()
                            },
                            enabled = editable,
                            icon = Lucide.Camera,
                            modifier = Modifier.weight(1f),
                        )
                        if (onSayIt != null) {
                            SecondaryButton(
                                text = stringResource(R.string.voice_say_it),
                                onClick = {
                                    keyboard?.hide()
                                    onSayIt()
                                },
                                enabled = editable,
                                icon = Lucide.Mic,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
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
        PrimaryButton(
            text = stringResource(if (state.saving) R.string.manual_saving else R.string.manual_save),
            onClick = onSave,
            enabled = state.canSave,
            busy = state.saving,
            modifier = Modifier.fillMaxWidth().background(colors.ground).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        )
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

/** Expense or income, as two halves of one filled pill. Expense selects in coral, income in the Income badge's green. */
@Composable
private fun TypeSelector(type: TxType, enabled: Boolean, onTypeChange: (TxType) -> Unit) {
    val colors = BudgetWiseTheme.colors
    val income = categoryColors(CategoryRepository.INCOME)
    SegmentedPill(Modifier.fillMaxWidth().selectableGroup(), padding = 4.dp) {
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
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(container)
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onTypeChange(option) }),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(label),
                    style = if (selected) BudgetWiseTheme.type.size16.semibold else BudgetWiseTheme.type.size16.medium,
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
    val big = type.size44.extraBold.tracked(-0.035f)
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
        minHeight = 84.dp,
        textStyle = big,
        trailing = {
            Text("Ks", style = type.size22.medium, color = BudgetWiseTheme.colors.muted, modifier = Modifier.padding(end = 18.dp))
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

/** The categories in two columns of 46 dp pills; the chosen one fills sage. */
@Composable
private fun CategoryPicker(state: AddManualUiState, enabled: Boolean, onCategoryChange: (String) -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val error = state.categoryError != null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(stringResource(R.string.manual_category))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.categories.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { name ->
                        val selected = name == state.category
                        val label = categoryLabel(name)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(CircleShape)
                                .background(if (selected) colors.sageContainer else Color.Transparent)
                                .border(1.dp, if (selected) colors.accent else if (error) colors.error else colors.chipBorder, CircleShape)
                                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onCategoryChange(name) })
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = categoryIcon(name),
                                contentDescription = null,
                                tint = if (selected) colors.onSageContainer else categoryColors(name).content,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                label,
                                style = (if (selected) type.size15.semibold else type.size15.medium).forContent(label),
                                color = if (selected) colors.onSageContainer else colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
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
        enabled = enabled,
        // Burmese descriptions wrap onto a few lines instead of scrolling sideways.
        singleLine = false,
        maxLines = 3,
        textStyle = BudgetWiseTheme.type.size16.forContent(state.description),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        error = error,
        // The count sits inside the box, at its end. Formatted like amounts: the Myanmar-digits setting decides.
        trailing = {
            Text(
                stringResource(
                    R.string.manual_description_count,
                    groupDigits(state.descriptionLength.toLong(), state.myanmarDigits),
                    groupDigits(max.toLong(), state.myanmarDigits),
                ),
                style = if (error != null) BudgetWiseTheme.type.size12.semibold else BudgetWiseTheme.type.size12,
                color = if (state.descriptionError == DescriptionError.TOO_LONG) colors.error else colors.muted,
                modifier = Modifier.padding(end = 14.dp),
            )
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
    val dateLabel = stringResource(R.string.manual_date)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(dateLabel, Modifier.clearAndSetSemantics { })
        // Drawn like the text fields above, but it opens the date sheet. TalkBack hears "double tap to change date".
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .fieldBox(colors, focused = false, error = error)
                .clickable(enabled = enabled, onClickLabel = changeLabel, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = "$dateLabel, $dateText" }
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Lucide.Calendar, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
            Text(dateText, style = type.size16, color = colors.ink, modifier = Modifier.weight(1f))
            Icon(Lucide.ChevronDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
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
internal fun DateSheet(date: LocalDate, today: LocalDate, myanmarDigits: Boolean, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
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
                                        style = if (selected) type.size15.semibold else type.size15,
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
            onSayIt = {},
            onScan = {},
            focusAmount = false,
        )
    }
}
