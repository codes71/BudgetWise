package app.budgetwise.ui.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.ui.common.CategoryBadge
import app.budgetwise.ui.common.EmptyState
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.monthLabel
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.YearMonth
import kotlin.math.abs

@Composable
fun BudgetsRoute(viewModel: BudgetsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BudgetsScreen(state = state, onRefresh = viewModel::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsScreen(state: BudgetsUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(title = { Text(stringResource(R.string.budgets_title)) }, scrollBehavior = scrollBehavior)
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state.showEmpty -> item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                            EmptyState(icon = R.drawable.ic_savings, message = stringResource(R.string.budgets_empty))
                        }
                    }
                    state.lines.isNotEmpty() -> {
                        item(key = "summary") { MonthCard(state) }
                        items(state.lines, key = { it.category }) { line -> BudgetCard(line, state.myanmarDigits) }
                    }
                }
            }
        }
    }
}

/** The whole month across every budget, in the same sage card as the transactions summary. */
@Composable
private fun MonthCard(state: BudgetsUiState) {
    val colors = MaterialTheme.colorScheme
    val spent = state.totalSpentKyat
    val limit = state.totalLimitKyat
    val spoken = spentOfLimitSpoken(spent, limit) + ", " + leftSpoken(limit - spent)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
    ) {
        Column(Modifier.padding(20.dp).semantics(mergeDescendants = true) {}) {
            Text(monthLabel(state.month), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.budgets_month_spent), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
                Text(
                    text = formatKyat(spent, state.myanmarDigits),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = stringResource(R.string.budgets_of_limit, formatKyat(limit, state.myanmarDigits)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                Bar(
                    progress = if (limit > 0) spent.toFloat() / limit else 0f,
                    color = if (spent > limit) colors.error else colors.onPrimaryContainer,
                    track = colors.surface.copy(alpha = 0.45f),
                )
            }
        }
    }
}

@Composable
private fun BudgetCard(line: BudgetLine, myanmarDigits: Boolean) {
    val colors = MaterialTheme.colorScheme
    val name = categoryLabel(line.category)
    val spentOfLimit = stringResource(
        R.string.budgets_spent_of_limit,
        formatKyat(line.spentKyat, myanmarDigits),
        formatKyat(line.limitKyat, myanmarDigits),
    )
    val spoken = "$name, ${spentOfLimitSpoken(line.spentKyat, line.limitKyat)}, " +
        if (line.over) overSpoken(-line.remainingKyat) else leftSpoken(line.remainingKyat)
    Card(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The name is written next to it, so the badge needs no description.
                CategoryBadge(line.category, contentDescription = null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(spentOfLimit, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = percent(line.progress, myanmarDigits),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (line.over) colors.error else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Bar(
                progress = line.progress,
                color = if (line.over) colors.error else colors.primary,
                track = colors.surfaceContainerHighest,
            )
            Spacer(Modifier.height(10.dp))
            if (line.over) {
                // Over budget is said in words and with an icon, not by the red alone.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = colors.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.budgets_over, formatKyat(-line.remainingKyat, myanmarDigits)),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.error,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.budgets_left, formatKyat(line.remainingKyat, myanmarDigits)),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                )
            }
        }
    }
}

@Composable
private fun Bar(progress: Float, color: Color, track: Color) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = color,
        trackColor = track,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

private fun percent(progress: Float, myanmarDigits: Boolean): String {
    val value = groupDigits((progress * 100).toLong(), myanmarDigits)
    return "$value%"
}

@Composable
private fun spentOfLimitSpoken(spent: Long, limit: Long) =
    stringResource(R.string.budgets_spent_of_limit_spoken, groupDigits(spent), groupDigits(limit))

@Composable
private fun leftSpoken(remaining: Long) =
    if (remaining < 0) overSpoken(abs(remaining)) else stringResource(R.string.budgets_left_spoken, groupDigits(remaining))

@Composable
private fun overSpoken(over: Long) = stringResource(R.string.budgets_over_spoken, groupDigits(over))

@Preview(showBackground = true)
@Composable
private fun BudgetsScreenPreview() {
    BudgetWiseTheme {
        BudgetsScreen(
            state = BudgetsUiState(
                month = YearMonth.of(2026, 10),
                loading = false,
                lines = listOf(
                    BudgetLine("Food and Drink Item", 150_000, 98_500),
                    BudgetLine("Groceries", 200_000, 61_200),
                    BudgetLine("Transport", 50_000, 57_000),
                ),
            ),
            onRefresh = {},
        )
    }
}
