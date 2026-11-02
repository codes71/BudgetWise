package app.budgetwise.ui.transactions

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.data.model.DailySpend
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.AppHeader
import app.budgetwise.ui.common.CategoryBadge
import app.budgetwise.ui.common.EmptyState
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.NoticeHost
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.NoticeVisuals
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SageRule
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.SessionNotice
import app.budgetwise.ui.common.SkeletonBar
import app.budgetwise.ui.common.SyncChip
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.dayLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.formatSignedKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.common.signedDigits
import app.budgetwise.ui.common.spokenAmount
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.voice.VoiceSetupViewModel
import app.budgetwise.ui.voice.rememberSayIt
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import java.time.Instant
import java.time.LocalDate

/** What a scan just saved, for the "Added 3 entries" notice and its Undo. */
data class ScanAdded(val clientIds: List<String>, val merchant: String?)

/**
 * The home screen: what is left this month, the month chart, and every entry by day. The shell gives it the header's
 * [chip] and the session notice (the early warning before a session ends), and opens `manual` for Add. A tap on an
 * entry opens it ([onOpenEntry], by clientId); on a scanned receipt's line, the receipt ([onOpenReceipt], by its id).
 */
@Composable
fun TransactionsRoute(
    chip: SyncChip?,
    sessionNotice: SessionNotice?,
    onChip: () -> Unit,
    onSettings: () -> Unit,
    onSignIn: () -> Unit,
    onAddManual: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenScan: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenReceipt: (String) -> Unit,
    scanAdded: ScanAdded?,
    onScanAddedShown: () -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel(),
    voiceSetup: VoiceSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sayIt = rememberSayIt(onOpenVoice, voiceSetup)
    val notices = remember { SnackbarHostState() }

    val refreshFailed = stringResource(R.string.transactions_refresh_failed)
    val unreachable = stringResource(R.string.transactions_unreachable)
    val offline = stringResource(R.string.transactions_refresh_offline)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            notices.showSnackbar(
                when (event) {
                    TransactionsEvent.Unreachable -> NoticeVisuals(NoticeTone.ATTENTION, Lucide.CloudAlert, refreshFailed, unreachable)
                    TransactionsEvent.WaitingForNetwork -> NoticeVisuals(NoticeTone.INFO, Lucide.CloudOff, refreshFailed, offline)
                },
            )
        }
    }

    // The early warning, once per visit to the app: the shell keeps this screen's saved state while another tab is
    // open, so coming back does not show it again.
    var warned by rememberSaveable { mutableStateOf(false) }
    val signIn by rememberUpdatedState(onSignIn)
    val soon = sessionNotice as? SessionNotice.ExpiringSoon
    val soonTitle = soon?.let { pluralStringResource(R.plurals.session_ending, it.daysLeft, it.daysLeft) }
    val soonDetail = stringResource(R.string.session_ending_detail)
    val signInLabel = stringResource(R.string.session_sign_in)
    LaunchedEffect(soonTitle) {
        if (soonTitle == null || warned) return@LaunchedEffect
        warned = true
        val visuals = NoticeVisuals(NoticeTone.INFO, Lucide.Clock, soonTitle, soonDetail, signInLabel, SnackbarDuration.Long)
        if (notices.showSnackbar(visuals) == SnackbarResult.ActionPerformed) signIn()
    }

    // One notice per swiped line. A new swipe restarts this effect (the ViewModel has already deleted the line before
    // it); Undo restores the line, anything else (timeout, swipe away) deletes it. A receipt's line is all its entries.
    val undo = stringResource(R.string.transactions_undo)
    val pending = state.pendingDelete
    val pendingRows = state.pendingDeleteRows
    val receipt = pendingRows.firstOrNull()?.receiptId != null
    val deleted = stringResource(if (receipt) R.string.receipt_deleted else R.string.transactions_deleted)
    val pendingName = if (receipt) {
        pendingRows.firstNotNullOfOrNull { it.merchant } ?: stringResource(R.string.receipt_title)
    } else {
        pendingRows.firstOrNull()?.description
    }
    val pendingDetail = pendingName?.let {
        stringResource(R.string.transactions_deleted_detail, it, formatKyat(pendingRows.sumOf { row -> row.amountKyat }, state.myanmarDigits))
    }
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        notices.currentSnackbarData?.dismiss()
        val visuals = NoticeVisuals(NoticeTone.NEUTRAL, null, deleted, pendingDetail, undo, SnackbarDuration.Short)
        val result = notices.showSnackbar(visuals)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(pending) else viewModel.commitDelete(pending)
    }
    // Leaving the screen ends the Undo window. A rotation (or another configuration change) does not: the screen
    // comes back with the same ViewModel and the effect above shows the notice again.
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.commitPendingDelete() }
    }

    // "Added 3 entries" after a scan, with Undo. The shell hands it over from the scan screen; it is forgotten once the
    // notice ends or the screen goes (another tab, a rotation), so a later visit never offers Undo on old entries.
    val addedCount = scanAdded?.clientIds?.size ?: 0
    val addedTitle = pluralStringResource(R.plurals.scan_added, addedCount, addedCount)
    val addedFrom = scanAdded?.merchant?.let { stringResource(R.string.scan_added_from, it) } ?: stringResource(R.string.scan_added_from_photo)
    val addedShown by rememberUpdatedState(onScanAddedShown)
    LaunchedEffect(scanAdded) {
        val added = scanAdded ?: return@LaunchedEffect
        val visuals = NoticeVisuals(NoticeTone.SUCCESS, Lucide.CircleCheck, addedTitle, addedFrom, undo, SnackbarDuration.Long)
        try {
            if (notices.showSnackbar(visuals) == SnackbarResult.ActionPerformed) viewModel.undoAdded(added.clientIds)
        } finally {
            addedShown()
        }
    }

    TransactionsScreen(
        state = state,
        chip = chip,
        notices = notices,
        onChip = onChip,
        onSettings = onSettings,
        onAddManual = onAddManual,
        onSayIt = sayIt,
        onScan = onOpenScan,
        onOpen = { line ->
            when (line) {
                is DayLine.Entry -> onOpenEntry(line.row.clientId)
                is DayLine.Receipt -> onOpenReceipt(line.receiptId)
            }
        },
        onDelete = viewModel::delete,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    state: TransactionsUiState,
    chip: SyncChip?,
    notices: SnackbarHostState,
    onChip: () -> Unit,
    onSettings: () -> Unit,
    onAddManual: () -> Unit,
    /** Null on a phone without the memory for voice: the first-day home offers typing and scanning only. */
    onSayIt: (() -> Unit)?,
    onScan: () -> Unit,
    onOpen: (DayLine) -> Unit,
    onDelete: (DayLine) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    Column(modifier.fillMaxSize().background(colors.ground)) {
        AppHeader(stringResource(R.string.transactions_title), chip, onChip = onChip, onSettings = onSettings)
        val pull = rememberPullToRefreshState()
        // The spinner only answers a pull; syncs that run on their own show in the header's chip.
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            state = pull,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pull,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = colors.raised,
                    color = colors.accent,
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            ) {
                when {
                    state.loading -> item(key = "loading", contentType = "loading") { LoadingSkeleton() }
                    state.isEmpty -> item(key = "empty") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyState(
                                icon = Lucide.Receipt,
                                title = stringResource(R.string.transactions_empty_title),
                                message = stringResource(R.string.transactions_empty_message),
                                modifier = Modifier.padding(bottom = 48.dp),
                                action = {
                                    PrimaryButton(stringResource(R.string.transactions_add_first), onAddManual, icon = Lucide.Plus)
                                },
                            )
                        }
                    }
                    else -> {
                        item(key = "month", contentType = "month") { MonthHead(state, onAddManual, onSayIt, onScan) }
                        days(state, onOpen, onDelete)
                    }
                }
            }
            NoticeHost(notices, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * "Left in October" with the big number and the sage rule, then the month's spending; before the first expense, the
 * way to add it instead of an empty chart.
 */
@Composable
private fun MonthHead(state: TransactionsUiState, onAddManual: () -> Unit, onSayIt: (() -> Unit)?, onScan: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val month = monthName(state.summary.month)
    val balance = state.summary.balance
    val spoken = stringResource(R.string.transactions_left_spoken, month, groupDigits(balance))
    val figure = groupDigits(balance, state.myanmarDigits)
    Column(Modifier.padding(top = 20.dp, bottom = 30.dp)) {
        Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
            SectionLabel(stringResource(R.string.transactions_left_in, month))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = figure,
                    // Seven figures and a sign still fit a phone's width at 44.
                    style = (if (figure.length > 8) type.size44 else type.size60).extraBold.tracked(-0.035f),
                    color = if (balance < 0) colors.coralText else colors.ink,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline(),
                )
                Text("Ks", style = type.size24.medium, color = colors.muted, modifier = Modifier.alignByBaseline())
            }
        }
        SageRule(Modifier.padding(top = 16.dp))
        if (state.noExpensesYet) {
            FirstExpense(onAddManual, onSayIt, onScan, Modifier.padding(top = 28.dp))
        } else {
            SpendingCard(state.pace, state.categories, state.myanmarDigits, Modifier.padding(top = 28.dp))
        }
    }
}

/** "Add your first expense": what it does to the number above, then the ways in: type it, scan a receipt, or say it. */
@Composable
private fun FirstExpense(onAddManual: () -> Unit, onSayIt: (() -> Unit)?, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.first_expense_title),
            style = type.size16.semibold,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.first_expense_detail),
            style = type.size14,
            color = colors.muted,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        WayRow(Lucide.Pencil, stringResource(R.string.first_expense_type), stringResource(R.string.first_expense_type_detail), onAddManual)
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        WayRow(Lucide.Camera, stringResource(R.string.scan_entry), stringResource(R.string.first_expense_scan_detail), onScan)
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        if (onSayIt != null) {
            WayRow(Lucide.Mic, stringResource(R.string.voice_say_it), stringResource(R.string.first_expense_say_detail), onSayIt)
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }
    }
}

/** One way to add the first expense: an outlined icon, what it is, and a chevron. One row-sized target. */
@Composable
private fun WayRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(colors.badgeFill).border(1.dp, colors.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.size16.medium, color = colors.ink)
            Text(detail, style = type.size13.forContent(detail), color = colors.muted)
        }
        Icon(Lucide.ChevronRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
    }
}

/** Each day: a header with the day's net over a hairline, then its lines divided by hairlines. */
private fun LazyListScope.days(state: TransactionsUiState, onOpen: (DayLine) -> Unit, onDelete: (DayLine) -> Unit) {
    state.days.forEachIndexed { dayIndex, day ->
        item(key = "day-${day.date}", contentType = "day") {
            DayHeader(day, state.today, state.myanmarDigits, Modifier.padding(top = if (dayIndex == 0) 0.dp else 18.dp))
        }
        itemsIndexed(
            items = day.lines,
            // A line brought back by Undo gets a new key: a new item, not the one that was swiped away.
            key = { _, line -> "${line.key}:${state.restored[line.key] ?: 0}" },
            contentType = { _, _ -> "row" },
        ) { index, line ->
            TransactionRow(
                line = line,
                last = index == day.lines.lastIndex,
                myanmarDigits = state.myanmarDigits,
                onOpen = { onOpen(line) },
                onDelete = { onDelete(line) },
                // No fade out: a swiped row has already slid off screen.
                modifier = Modifier.animateItem(fadeOutSpec = null),
            )
        }
    }
}

@Composable
private fun DayHeader(day: DaySection, today: LocalDate, myanmarDigits: Boolean, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val net = day.rows.sumOf { if (it.type == TxType.EXPENSE) -it.amountKyat else it.amountKyat }
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(
                text = dayLabel(day.date, today, myanmarDigits),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Text("${signedDigits(net, myanmarDigits)} Ks", style = BudgetWiseTheme.type.size13.medium, color = colors.muted)
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
    }
}

/**
 * An entry, or a scanned receipt's entries as one line: tap to open it, swipe left to delete it (with Undo). Under an
 * entry's description, a mic or a scan mark says it was said or read off a KPay screen; typed ones have none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionRow(
    line: DayLine,
    last: Boolean,
    myanmarDigits: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val dismissState = rememberSwipeToDismissBoxState()
    val deleteLabel = stringResource(R.string.transactions_delete)
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        onDismiss = { onDelete() },
        backgroundContent = {
            // Drawn only while the row moves.
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Row(
                    modifier = Modifier.fillMaxSize().background(colors.deleteReveal).padding(end = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Trash, contentDescription = null, tint = colors.onDeleteReveal, modifier = Modifier.size(20.dp))
                    Text(deleteLabel, style = type.size14.semibold, color = colors.onDeleteReveal)
                }
            }
        },
    ) {
        Column(Modifier.background(colors.ground)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(role = Role.Button, onClick = onOpen)
                    // TalkBack cannot swipe a row; Delete is offered as an action on it instead.
                    .semantics(mergeDescendants = true) {
                        customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (line) {
                    is DayLine.Entry -> EntryContent(line.row, myanmarDigits)
                    is DayLine.Receipt -> ReceiptContent(line, myanmarDigits)
                }
            }
            if (!last) HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }
    }
}

@Composable
private fun RowScope.EntryContent(row: Transaction, myanmarDigits: Boolean) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    // The category name is written next to the badge, so the badge itself stays silent.
    CategoryBadge(row.category, contentDescription = null)
    Column(Modifier.weight(1f)) {
        Text(
            text = row.description,
            style = type.size16.medium.forContent(row.description),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            when (row.source) {
                Source.VOICE -> SourceMark(Lucide.Mic, colors.muted, stringResource(R.string.transactions_by_voice))
                // A receipt's entries are a receipt line; a scanned entry here came from a KPay screen.
                Source.SCAN -> SourceMark(Lucide.ScanLine, colors.accent, stringResource(R.string.scan_scanned))
                Source.MANUAL, Source.WEB -> Unit
            }
            val category = categoryLabel(row.category)
            Text(category, style = type.size13.forContent(category), color = colors.muted, maxLines = 1)
        }
    }
    Amount(row.amountKyat, row.type, myanmarDigits)
}

/** The shop, "Receipt · 2 entries" (or "Scanned twice?" in amber), and what its entries add up to. */
@Composable
private fun RowScope.ReceiptContent(line: DayLine.Receipt, myanmarDigits: Boolean) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(colors.accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Lucide.ReceiptText, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
    }
    Column(Modifier.weight(1f)) {
        val shop = line.merchant ?: stringResource(R.string.receipt_title)
        Text(shop, style = type.size16.medium.forContent(shop), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val (icon, tint, text) = if (line.scannedTwice) {
                Triple(Lucide.CircleAlert, colors.amber, stringResource(R.string.receipt_scanned_twice))
            } else {
                val count = line.entries.size
                Triple(Lucide.ScanLine, colors.accent, pluralStringResource(R.plurals.receipt_row_entries, count, count))
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            Text(text, style = type.size13, color = if (line.scannedTwice) colors.amber else colors.muted, maxLines = 1)
        }
    }
    Amount(line.amountKyat, TxType.EXPENSE, myanmarDigits)
}

/** How an entry was added, before its category. No text beside it says so, so TalkBack reads the icon. */
@Composable
private fun SourceMark(icon: ImageVector, tint: Color, label: String) {
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(13.dp))
}

/** The signed amount, read the way it is meant ("1,500 kyat spent"). */
@Composable
private fun Amount(amountKyat: Long, type: TxType, myanmarDigits: Boolean) {
    val spoken = spokenAmount(amountKyat, type)
    Text(
        text = formatSignedKyat(amountKyat, type, myanmarDigits),
        style = BudgetWiseTheme.type.size16.semibold,
        color = if (type == TxType.INCOME) BudgetWiseTheme.colors.accent else BudgetWiseTheme.colors.ink,
        softWrap = false,
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}

/** The month head and one day group, as grey blocks of the same size, while Room answers the first time. */
@Composable
private fun LoadingSkeleton() {
    val colors = BudgetWiseTheme.colors
    val loading = stringResource(R.string.transactions_loading)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = loading }) {
        Column(Modifier.padding(top = 20.dp, bottom = 30.dp)) {
            SkeletonBar(128.dp, 12.dp, color = colors.surface)
            SkeletonBar(220.dp, 52.dp, Modifier.padding(top = 12.dp), color = colors.surface)
            SkeletonBar(160.dp, 16.dp, Modifier.padding(top = 44.dp), color = colors.surface)
            SkeletonBar(null, 140.dp, Modifier.padding(top = 16.dp), color = colors.surface)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SkeletonBar(64.dp, 12.dp, color = colors.surface)
            Box(Modifier.weight(1f))
            SkeletonBar(72.dp, 12.dp, color = colors.surface)
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        repeat(3) { i ->
            if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            Row(
                Modifier.fillMaxWidth().height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(colors.surface))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBar(150.dp, 14.dp, color = colors.surface)
                    SkeletonBar(92.dp, 12.dp, color = colors.surface)
                }
                SkeletonBar(68.dp, 14.dp, color = colors.surface)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TransactionsScreenPreview() {
    val today = LocalDate.parse("2026-10-23")
    fun row(description: String, amount: Long, date: LocalDate, type: TxType, category: String, source: Source = Source.MANUAL) =
        Transaction(
            clientId = description, serverId = null, type = type, amountKyat = amount, category = category,
            description = description, date = date, source = source, transcript = null,
            updatedAt = Instant.EPOCH, syncState = SyncState.SYNCED,
        )
    val receipt = listOf(
        row("Ocean Supercenter, Groceries", 16307, today, TxType.EXPENSE, "Groceries", Source.SCAN),
        row("Ocean Supercenter, Other", 30000, today, TxType.EXPENSE, "Other", Source.SCAN),
    ).map { it.copy(receiptId = "ocean", merchant = "Ocean Supercenter") }
    val rows = receipt + listOf(
        row("လက်ဖက်ရည်နှင့် မုန့်ဟင်းခါး", 3500, today, TxType.EXPENSE, "Food and Drink Item"),
        row("Bus to Sule", 600, today, TxType.EXPENSE, "Transport", Source.VOICE),
        row("October salary", 850000, today.minusDays(1), TxType.INCOME, "Income"),
        row("Electricity bill", 24500, today.minusDays(2), TxType.EXPENSE, "Utilities"),
    )
    val daily = listOf(
        DailySpend(today.withDayOfMonth(3), "Groceries", 15_400),
        DailySpend(today.withDayOfMonth(15), "Food and Drink Item", 26_400),
        DailySpend(today, "Food and Drink Item", 3_500),
        DailySpend(today.minusMonths(1).withDayOfMonth(8), "Groceries", 22_000),
        DailySpend(today.minusMonths(1).withDayOfMonth(19), "Health", 24_000),
    )
    BudgetWiseTheme {
        TransactionsScreen(
            state = TransactionsUiState(
                today = today,
                loading = false,
                days = groupByDay(rows),
                summary = monthSummary(rows, java.time.YearMonth.from(today)),
                pace = monthPace(daily, today),
                categories = categoryBreakdown(daily, today),
            ),
            chip = SyncChip.SYNCED,
            notices = remember { SnackbarHostState() },
            onChip = {},
            onSettings = {},
            onAddManual = {},
            onSayIt = {},
            onScan = {},
            onOpen = {},
            onDelete = {},
            onRefresh = {},
        )
    }
}
