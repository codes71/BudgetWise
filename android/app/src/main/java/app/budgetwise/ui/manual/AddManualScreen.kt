package app.budgetwise.ui.manual

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.KyatGroupingTransformation
import app.budgetwise.ui.common.categoryColors
import app.budgetwise.ui.common.categoryIcon
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.dayLabel
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.localizedDate
import app.budgetwise.ui.common.pickerMillisToDate
import app.budgetwise.ui.common.toMyanmarDigits
import app.budgetwise.ui.common.toPickerMillis
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.LocalDate

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

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manual_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
        bottomBar = { SaveBar(state, onSave) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            TypeSelector(state.type, onTypeChange)
            AmountField(state, onAmountChange, amountFocus, onNext = { descriptionFocus.requestFocus() })
            CategoryPicker(state, onCategoryChange)
            DescriptionField(state, onDescriptionChange, descriptionFocus, onDone = { keyboard?.hide() })
            DateField(state, onClick = {
                keyboard?.hide()
                pickingDate = true
            })
        }
    }

    if (pickingDate) {
        DatePickerFor(
            date = state.date,
            today = state.today,
            onPick = {
                onDateChange(it)
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }
}

@Composable
private fun TypeSelector(type: TxType, onTypeChange: (TxType) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val options = listOf(
        Triple(TxType.EXPENSE, R.string.manual_expense, R.drawable.ic_north_east),
        Triple(TxType.INCOME, R.string.manual_income, R.drawable.ic_south_west),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (option, label, icon) ->
            val selected = option == type
            // Expense selects in coral, income in sage, the same hues as the amounts in the list.
            val (container, content) = if (option == TxType.EXPENSE) {
                colors.tertiaryContainer to colors.onTertiaryContainer
            } else {
                colors.primaryContainer to colors.onPrimaryContainer
            }
            SegmentedButton(
                selected = selected,
                onClick = { onTypeChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(activeContainerColor = container, activeContentColor = content),
                icon = { Icon(painterResource(icon), contentDescription = null, Modifier.size(SegmentedButtonDefaults.IconSize)) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun AmountField(
    state: AddManualUiState,
    onAmountChange: (String) -> Unit,
    focus: FocusRequester,
    onNext: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val big = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold)
    val tint = if (state.type == TxType.EXPENSE) colors.tertiary else colors.primary
    val transformation = remember(state.myanmarDigits) { KyatGroupingTransformation(state.myanmarDigits) }
    OutlinedTextField(
        value = state.amountDigits,
        onValueChange = onAmountChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        textStyle = big.copy(color = tint),
        label = { Text(stringResource(R.string.manual_amount)) },
        placeholder = { Text(if (state.myanmarDigits) toMyanmarDigits("0") else "0", style = big) },
        suffix = { Text("Ks", style = MaterialTheme.typography.headlineSmall, color = colors.onSurfaceVariant) },
        visualTransformation = transformation,
        singleLine = true,
        isError = state.amountError != null,
        supportingText = state.amountError?.let { error ->
            {
                Text(
                    stringResource(
                        when (error) {
                            AmountError.MISSING -> R.string.manual_amount_error_missing
                            AmountError.ZERO -> R.string.manual_amount_error_zero
                        },
                    ),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(state: AddManualUiState, onCategoryChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Text(
            stringResource(R.string.manual_category),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.categories.forEach { name ->
                val selected = name == state.category
                FilterChip(
                    selected = selected,
                    onClick = { onCategoryChange(name) },
                    label = { Text(categoryLabel(name)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(if (selected) R.drawable.ic_check else categoryIcon(name)),
                            contentDescription = null,
                            tint = if (selected) colors.onPrimaryContainer else categoryColors(name).content,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.primaryContainer,
                        selectedLabelColor = colors.onPrimaryContainer,
                    ),
                )
            }
        }
        state.categoryError?.let {
            Text(
                stringResource(R.string.manual_category_error),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun DescriptionField(
    state: AddManualUiState,
    onDescriptionChange: (String) -> Unit,
    focus: FocusRequester,
    onDone: () -> Unit,
) {
    val max = AddManualViewModel.MAX_DESCRIPTION
    OutlinedTextField(
        value = state.description,
        onValueChange = onDescriptionChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        label = { Text(stringResource(R.string.manual_description)) },
        placeholder = { Text(stringResource(R.string.manual_description_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_notes), contentDescription = null) },
        isError = state.descriptionError != null,
        supportingText = {
            Row {
                val error = when (state.descriptionError) {
                    DescriptionError.BLANK -> stringResource(R.string.manual_description_error_blank)
                    DescriptionError.TOO_LONG -> pluralStringResource(R.plurals.manual_description_error_too_long, max, max)
                    null -> ""
                }
                Text(error, modifier = Modifier.weight(1f))
                // Formatted like amounts: the Myanmar-digits setting decides, not the phone's locale.
                Text(
                    stringResource(
                        R.string.manual_description_count,
                        groupDigits(state.descriptionLength.toLong(), state.myanmarDigits),
                        groupDigits(max.toLong(), state.myanmarDigits),
                    ),
                )
            }
        },
        // Burmese descriptions wrap onto more lines instead of scrolling sideways.
        minLines = 1,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

@Composable
private fun DateField(state: AddManualUiState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val error = state.dateError != null
    val changeLabel = stringResource(R.string.manual_date_change)
    val dateText = if (state.date == state.today || state.date == state.today.minusDays(1)) {
        "${dayLabel(state.date, state.today)}, ${localizedDate(state.date, "dMMMMy")}"
    } else {
        localizedDate(state.date, "EEEEdMMMMy")
    }
    Column {
        // Drawn like the text fields above (4 dp corners, outline), but it opens the date picker.
        val shape = MaterialTheme.shapes.extraSmall
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(onClickLabel = changeLabel, role = Role.Button, onClick = onClick),
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, if (error) colors.error else colors.outline),
        ) {
            Row(
                modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_calendar_today), contentDescription = null, tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.manual_date), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(dateText, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.width(12.dp))
                // Like a dropdown field: it opens a picker. TalkBack hears "double tap to change date".
                Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = colors.onSurfaceVariant)
            }
        }
        if (error) {
            Text(
                stringResource(R.string.manual_date_error_future),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerFor(date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val lastDay = today.toPickerMillis()
    val selectable = remember(today) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= lastDay

            override fun isSelectableYear(year: Int) = year <= today.year
        }
    }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.toPickerMillis(), selectableDates = selectable)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { pickerState.selectedDateMillis?.let { onPick(pickerMillisToDate(it)) } ?: onDismiss() },
            ) {
                Text(stringResource(R.string.action_ok), style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun SaveBar(state: AddManualUiState, onSave: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (state.saveFailed) {
                Text(
                    stringResource(R.string.manual_save_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                    modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            // Disabled while saving (TalkBack says so) but kept in the brand color, with the spinner saying it is busy.
            Button(
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = if (state.saving) {
                    ButtonDefaults.buttonColors(disabledContainerColor = colors.primary, disabledContentColor = colors.onPrimary)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.manual_saving), style = MaterialTheme.typography.labelLarge)
                } else {
                    Text(stringResource(R.string.manual_save), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AddManualScreenPreview() {
    val today = LocalDate.parse("2026-10-21")
    BudgetWiseTheme {
        AddManualScreen(
            state = AddManualUiState(
                today = today,
                amountDigits = "12500",
                category = "Food and Drink Item",
                description = "လက်ဖက်ရည်နှင့် မုန့်",
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
