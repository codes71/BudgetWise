package app.budgetwise.ui.budgets

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.EmptyState
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.NoticeHost
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.NoticeVisuals
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.SkeletonBar
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.transactions.DayHeader
import app.budgetwise.ui.transactions.DaySection
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import app.budgetwise.ui.transactions.DayLine
import app.budgetwise.ui.transactions.TransactionRow

/** The category drill-down keeps the existing entry editor, swipe Delete and Undo interactions. */
@Composable
fun BudgetTransactionsRoute(
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    viewModel: BudgetTransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshToday()
        onPauseOrDispose { }
    }
    val notices = remember { SnackbarHostState() }
    val pending = state.pendingDelete
    val deleted = stringResource(R.string.transactions_deleted)
    val detail = pending?.row?.let { stringResource(R.string.transactions_deleted_detail, it.description, formatKyat(it.amountKyat, state.myanmarDigits)) }
    val undo = stringResource(R.string.transactions_undo)
    LaunchedEffect(pending?.clientId) {
        val row = pending ?: return@LaunchedEffect
        notices.currentSnackbarData?.dismiss()
        val result = notices.showSnackbar(NoticeVisuals(NoticeTone.NEUTRAL, null, deleted, detail, undo, SnackbarDuration.Short))
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(row.clientId) else viewModel.commitDelete(row.clientId)
    }
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.commitPendingDelete() }
    }
    BudgetTransactionsScreen(state, onBack, onOpenEntry, viewModel::delete, notices)
}

@Composable
fun BudgetTransactionsScreen(
    state: BudgetTransactionsUiState,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onDelete: (String) -> Unit,
    notices: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = if (state.category == EVERYTHING_ELSE) stringResource(R.string.budgets_everything_else) else categoryLabel(state.category)
    Column(modifier.fillMaxSize().background(colors.ground).windowInsetsPadding(WindowInsets.navigationBars)) {
        BackHeader(name, onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                if (state.loading) {
                    item(key = "loading") {
                        val loading = stringResource(R.string.budgets_loading)
                        Column(Modifier.padding(top = 26.dp).clearAndSetSemantics { contentDescription = loading }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SkeletonBar(180.dp, 12.dp, color = colors.surface)
                            SkeletonBar(230.dp, 44.dp, color = colors.surface)
                            SkeletonBar(180.dp, 14.dp, color = colors.surface)
                        }
                    }
                } else {
                    item(key = "summary") {
                        Column(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 28.dp)) {
                            SectionLabel(stringResource(R.string.budget_transactions_month, monthName(state.month)))
                            val line = state.line
                            if (line != null) {
                                val figure = groupDigits(kotlin.math.abs(line.remainingKyat), state.myanmarDigits)
                                // Keep every digit visible, with the unit below when large text fills the row.
                                FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BasicText(
                                        figure,
                                        style = type.size44.extraBold.copy(color = if (line.over) colors.coralText else colors.ink),
                                        autoSize = TextAutoSize.StepBased(minFontSize = type.size14.fontSize, maxFontSize = type.size44.fontSize),
                                        maxLines = 1,
                                        modifier = Modifier.alignByBaseline(),
                                    )
                                    Text(
                                        stringResource(if (line.over) R.string.budgets_ks_over else R.string.budgets_ks_left),
                                        style = type.size18.medium,
                                        color = if (line.over) colors.coralText else colors.muted,
                                        modifier = Modifier.alignByBaseline(),
                                    )
                                }
                                Bar(line.progress, if (line.over) colors.coral else colors.accent, 6.dp, Modifier.padding(top = 16.dp))
                                Text(
                                    stringResource(R.string.budgets_spent_of_total, groupDigits(line.spentKyat, state.myanmarDigits), groupDigits(line.limitKyat, state.myanmarDigits)),
                                    style = type.size14,
                                    color = colors.muted,
                                    modifier = Modifier.padding(top = 10.dp),
                                )
                            } else {
                                Text(stringResource(R.string.budget_transactions_plan_changed), style = type.size14, color = colors.muted, modifier = Modifier.padding(top = 12.dp))
                            }
                            Text(
                                pluralStringResource(R.plurals.budget_transactions_count, state.count, groupDigits(state.count.toLong(), state.myanmarDigits)),
                                style = type.size13,
                                color = colors.muted,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                    }
                    if (state.days.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                icon = Lucide.Receipt,
                                title = stringResource(R.string.budget_transactions_empty_title),
                                message = stringResource(R.string.budget_transactions_empty_detail),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            )
                        }
                    }
                    state.days.forEachIndexed { dayIndex, day ->
                        item(key = "day:${day.date}", contentType = "day") {
                            DayHeader(day, state.today, state.myanmarDigits, Modifier.padding(top = if (dayIndex == 0) 0.dp else 18.dp))
                        }
                        itemsIndexed(day.rows, key = { _, row -> "entry:${row.clientId}:${state.restored[row.clientId] ?: 0}" }, contentType = { _, _ -> "entry" }) { index, row ->
                            TransactionRow(
                                line = DayLine.Entry(row),
                                last = index == day.rows.lastIndex,
                                myanmarDigits = state.myanmarDigits,
                                onOpen = { onOpenEntry(row.clientId) },
                                onDelete = { onDelete(row.clientId) },
                            )
                        }
                    }
                }
            }
            NoticeHost(notices, Modifier.align(Alignment.BottomCenter))
        }
    }
}


@Preview(name = "Category expenses, light", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "Category expenses, dark", showBackground = true, widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BudgetTransactionsPreview() {
    val date = LocalDate.of(2026, 11, 3)
    val manual = Transaction("rice", "server-rice", TxType.EXPENSE, 48_000, "Groceries", "Rice and cooking oil", date, Source.MANUAL, null, Instant.EPOCH, SyncState.SYNCED)
    val receipt = manual.copy(clientId = "receipt-groceries", amountKyat = 24_500, description = "City Mart, Groceries", source = Source.SCAN, receiptId = "city-mart", merchant = "City Mart")
    BudgetWiseTheme {
        BudgetTransactionsScreen(
            state = BudgetTransactionsUiState(YearMonth.from(date), "Groceries", date, loading = false, line = BudgetLine("Groceries", 350_000, 72_500), days = listOf(DaySection(date, listOf(manual, receipt)))),
            onBack = {}, onOpenEntry = {}, onDelete = {}, notices = remember { SnackbarHostState() },
        )
    }
}
