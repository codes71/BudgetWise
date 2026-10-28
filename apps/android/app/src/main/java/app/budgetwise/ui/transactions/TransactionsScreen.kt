package app.budgetwise.ui.transactions

import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.CategoryBadge
import app.budgetwise.ui.common.EmptyState
import app.budgetwise.ui.common.SessionBanner
import app.budgetwise.ui.common.SessionNotice
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.dayLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.formatSignedKyat
import app.budgetwise.ui.common.monthLabel
import app.budgetwise.ui.common.spokenAmount
import app.budgetwise.ui.common.spokenKyat
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * The home screen: this month's totals and every entry by day. The shell gives it the session banner (expired or
 * expiring soon) with its sign-in action, and opens `manual` for Add.
 */
@Composable
fun TransactionsRoute(
    sessionNotice: SessionNotice?,
    onSignIn: () -> Unit,
    onAddManual: () -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val unreachable = stringResource(R.string.transactions_unreachable)
    val waiting = stringResource(R.string.transactions_waiting_for_network)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                TransactionsEvent.Unreachable -> unreachable
                TransactionsEvent.WaitingForNetwork -> waiting
            }
            snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    // One snackbar per swiped row. A new swipe restarts this effect (the ViewModel has already deleted the row
    // before it); Undo restores the row, anything else (timeout, swipe away) deletes it.
    val deleted = stringResource(R.string.transactions_deleted)
    val undo = stringResource(R.string.transactions_undo)
    val pending = state.pendingDelete
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(deleted, actionLabel = undo, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(pending) else viewModel.commitDelete(pending)
    }
    // Leaving the screen ends the Undo window. A rotation (or another configuration change) does not: the screen
    // comes back with the same ViewModel and the effect above shows the snackbar again.
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.commitPendingDelete() }
    }

    TransactionsScreen(
        state = state,
        sessionNotice = sessionNotice,
        snackbarHostState = snackbar,
        onSignIn = onSignIn,
        onAddManual = onAddManual,
        onDelete = viewModel::delete,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    state: TransactionsUiState,
    sessionNotice: SessionNotice?,
    snackbarHostState: SnackbarHostState,
    onSignIn: () -> Unit,
    onAddManual: () -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.transactions_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // The empty state has its own, larger Add button.
            if (!state.loading && !state.isEmpty) {
                val add = stringResource(R.string.action_add)
                ExtendedFloatingActionButton(
                    onClick = onAddManual,
                    expanded = fabExpanded,
                    // Collapsed (scrolled down), the icon is all there is, so it carries the label.
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = if (fabExpanded) null else add) },
                    text = { Text(add, style = MaterialTheme.typography.labelLarge) },
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    // Room for the FAB over the last row.
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (sessionNotice != null) {
                    item(key = "session") {
                        SessionBanner(sessionNotice, onSignIn, Modifier.fillMaxWidth().padding(bottom = 10.dp))
                    }
                }
                if (state.isEmpty) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                            EmptyState(
                                icon = R.drawable.ic_receipt_long,
                                title = stringResource(R.string.transactions_empty_title),
                                message = stringResource(R.string.transactions_empty_message),
                                action = {
                                    Button(onClick = onAddManual, modifier = Modifier.heightIn(min = 52.dp)) {
                                        Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.transactions_add_first), style = MaterialTheme.typography.labelLarge)
                                    }
                                },
                            )
                        }
                    }
                } else if (!state.loading) {
                    item(key = "summary") {
                        MonthSummaryCard(state.summary, state.myanmarDigits, Modifier.padding(bottom = 8.dp))
                    }
                }
                state.days.forEach { day ->
                    item(key = "day-${day.date}", contentType = "day") { DayHeader(day.date, state.today) }
                    itemsIndexed(
                        items = day.rows,
                        // A row brought back by Undo gets a new key: a new item, not the one that was swiped away.
                        key = { _, row -> "${row.clientId}:${state.restored[row.clientId] ?: 0}" },
                        contentType = { _, _ -> "row" },
                    ) { index, row ->
                        TransactionRow(
                            row = row,
                            shape = groupedShape(index, day.rows.size),
                            myanmarDigits = state.myanmarDigits,
                            onDelete = onDelete,
                            // No fade out: a swiped row has already slid off screen.
                            modifier = Modifier.animateItem(fadeOutSpec = null),
                        )
                    }
                }
            }
        }
    }
}

/** Rows of one day read as one rounded group: large corners at its ends, small ones between rows. */
private fun groupedShape(index: Int, count: Int, outer: Dp = 16.dp, inner: Dp = 4.dp): Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
    Text(
        text = dayLabel(date, today),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun MonthSummaryCard(summary: MonthSummary, myanmarDigits: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(monthLabel(summary.month), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.transactions_balance), style = MaterialTheme.typography.bodyMedium)
                val balance = spokenKyat(summary.balance)
                Text(
                    text = formatKyat(summary.balance, myanmarDigits),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.semantics { contentDescription = balance },
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryFigure(
                    icon = R.drawable.ic_north_east,
                    label = stringResource(R.string.transactions_spent),
                    value = formatKyat(summary.spent, myanmarDigits),
                    spoken = spokenAmount(summary.spent, TxType.EXPENSE),
                    modifier = Modifier.weight(1f),
                )
                SummaryFigure(
                    icon = R.drawable.ic_south_west,
                    label = stringResource(R.string.transactions_income),
                    value = formatKyat(summary.income, myanmarDigits),
                    spoken = spokenAmount(summary.income, TxType.INCOME),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryFigure(@DrawableRes icon: Int, label: String, value: String, spoken: String, modifier: Modifier = Modifier) {
    // A lighter (light theme) or darker (dark theme) well on the card; it only raises the text contrast.
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { contentDescription = spoken },
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(
    row: Transaction,
    shape: Shape,
    myanmarDigits: Boolean,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dismissState = rememberSwipeToDismissBoxState()
    val deleteLabel = stringResource(R.string.transactions_delete)
    val spoken = spokenAmount(row.amountKyat, row.type)
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.clip(shape),
        enableDismissFromStartToEnd = false,
        onDismiss = { onDelete(row.clientId) },
        backgroundContent = {
            Box(
                modifier = Modifier.fillMaxSize().background(colors.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = colors.onErrorContainer)
            }
        },
    ) {
        Surface(color = colors.surfaceContainerLow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    // TalkBack cannot swipe a row; Delete is offered as an action on it instead.
                    .semantics(mergeDescendants = true) {
                        customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(row.clientId); true })
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The category name is written next to the badge, so the badge itself stays silent.
                CategoryBadge(row.category, contentDescription = null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    // Burmese descriptions wrap; never cut to one line.
                    Text(row.description, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                    SecondaryLine(row)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = formatSignedKyat(row.amountKyat, row.type, myanmarDigits),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.type == TxType.EXPENSE) colors.tertiary else colors.primary,
                    softWrap = false,
                    modifier = Modifier.semantics { contentDescription = spoken },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecondaryLine(row: Transaction) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = categoryLabel(row.category),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (row.syncState) {
            SyncState.SYNCED -> Unit
            SyncState.PENDING -> StatusPill(
                icon = R.drawable.ic_schedule,
                text = stringResource(R.string.transactions_not_synced),
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SyncState.FAILED -> StatusPill(
                icon = R.drawable.ic_error,
                text = stringResource(R.string.transactions_sync_failed),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StatusPill(@DrawableRes icon: Int, text: String, container: Color, content: Color) {
    Row(
        modifier = Modifier.clip(CircleShape).background(container).padding(start = 6.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

@Preview(showBackground = true)
@Composable
private fun TransactionsScreenPreview() {
    val today = LocalDate.parse("2026-10-21")
    fun row(description: String, amount: Long, date: LocalDate, type: TxType, category: String, sync: SyncState) =
        Transaction(
            clientId = description, serverId = null, type = type, amountKyat = amount, category = category,
            description = description, date = date, source = Source.MANUAL, transcript = null,
            updatedAt = Instant.EPOCH, syncState = sync,
        )
    val rows = listOf(
        row("လက်ဖက်ရည်နှင့် မုန့်", 3500, today, TxType.EXPENSE, "Food and Drink Item", SyncState.PENDING),
        row("Grab to office", 6000, today, TxType.EXPENSE, "Transport", SyncState.SYNCED),
        row("Salary", 850000, today.minusDays(1), TxType.INCOME, "Other", SyncState.SYNCED),
        row("City Mart", 42300, today.minusDays(3), TxType.EXPENSE, "Groceries", SyncState.FAILED),
    )
    BudgetWiseTheme {
        TransactionsScreen(
            state = TransactionsUiState(
                today = today,
                loading = false,
                days = groupByDay(rows),
                summary = monthSummary(rows, YearMonth.from(today)),
            ),
            sessionNotice = null,
            snackbarHostState = remember { SnackbarHostState() },
            onSignIn = {},
            onAddManual = {},
            onDelete = {},
            onRefresh = {},
        )
    }
}
