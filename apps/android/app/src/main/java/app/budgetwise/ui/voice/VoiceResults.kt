package app.budgetwise.ui.voice

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.VoiceEntry
import app.budgetwise.data.repo.VoiceMetric
import app.budgetwise.data.repo.VoiceQuestion
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.CategoryBadge
import app.budgetwise.ui.common.FieldLabel
import app.budgetwise.ui.common.KitTextField
import app.budgetwise.ui.common.KyatGroupingTransformation
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.Notice
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SageRule
import app.budgetwise.ui.common.SecondaryButton
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.categoryColors
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.dayLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.localizedDate
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.common.typedKyatDigits
import app.budgetwise.ui.manual.DateSheet
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * "Check what you said": what was heard, the total, one row per entry (tap to change it), a note when a day was
 * guessed, and Add. Nothing is saved until Add.
 */
@Composable
internal fun ReviewScreen(state: VoiceUiState, review: ReviewState, actions: VoiceActions, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val count = review.entries.size
    VoicePage(
        title = stringResource(R.string.voice_review_title),
        onClose = actions.onClose,
        modifier = modifier,
        footer = {
            MicLink(stringResource(R.string.voice_say_again), actions.onSayAgain, enabled = !review.saving)
            PrimaryButton(
                text = pluralStringResource(R.plurals.voice_add_entries, count, count),
                onClick = actions.onSave,
                enabled = count > 0,
                busy = review.saving,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        },
    ) {
        if (review.heard.isNotBlank()) {
            SectionLabel(stringResource(R.string.voice_we_heard), Modifier.padding(bottom = 10.dp))
            HeardQuote(review.heard)
        }
        SectionLabel(
            stringResource(if (review.totalType == TxType.EXPENSE) R.string.voice_total else R.string.voice_total_received),
            Modifier.padding(top = 24.dp),
        )
        BigKyat(
            figure = groupDigits(review.totalKyat, state.myanmarDigits),
            spoken = stringResource(R.string.kyat_amount, groupDigits(review.totalKyat)),
            modifier = Modifier.padding(top = 4.dp),
        )
        SectionLabel(pluralStringResource(R.plurals.voice_entries_count, count, count), Modifier.padding(top = 28.dp, bottom = 10.dp))
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        review.entries.forEachIndexed { index, entry ->
            EntryRow(entry, state, enabled = !review.saving, onClick = { editing = index })
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }
        if (review.guessedDates > 0) {
            val today = localizedDate(state.today, "EEEEMMMMd", state.myanmarDigits)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Lucide.Calendar, contentDescription = null, tint = colors.amber, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                Text(
                    pluralStringResource(R.plurals.voice_no_day_said, review.guessedDates, today),
                    style = type.size13,
                    color = colors.muted,
                )
            }
        }
        if (review.saveFailed) {
            Notice(
                tone = NoticeTone.ERROR,
                icon = Lucide.CircleAlert,
                title = stringResource(R.string.manual_save_failed),
                detail = stringResource(R.string.voice_save_failed_detail),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
    val index = editing
    val entry = index?.let { review.entries.getOrNull(it) }
    if (index != null && entry != null) {
        EntrySheet(
            entry = entry,
            state = state,
            onDone = {
                actions.onEditEntry(index, it)
                editing = null
            },
            onRemove = {
                actions.onRemoveEntry(index)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** An entry to check: its badge, category (with a chevron: tap to change), what and when, and the amount. */
@Composable
private fun EntryRow(entry: VoiceEntry, state: VoiceUiState, enabled: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.voice_edit_title), onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CategoryBadge(entry.category, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(categoryLabel(entry.category), style = type.size16.medium.forContent(entry.category), color = colors.ink, maxLines = 1)
                Icon(Lucide.ChevronDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
            }
            val detail = "${entry.description} · ${dayLabel(entry.date, state.today, state.myanmarDigits)}"
            Text(detail, style = type.size13.forContent(detail), color = colors.muted, maxLines = 2)
        }
        val amount = formatKyat(entry.amountKyat, state.myanmarDigits)
        Text(
            if (entry.type == TxType.INCOME) "+$amount" else amount,
            style = type.size16.semibold,
            color = if (entry.type == TxType.INCOME) colors.accent else colors.ink,
            maxLines = 1,
        )
    }
}

/**
 * Change an entry before it is saved: expense or income, amount, category (expense only), description and day. Done
 * keeps the change; Remove drops the entry.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EntrySheet(
    entry: VoiceEntry,
    state: VoiceUiState,
    onDone: (VoiceEntry) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    var kind by rememberSaveable { mutableStateOf(entry.type) }
    var digits by rememberSaveable { mutableStateOf(entry.amountKyat.toString()) }
    var category by rememberSaveable { mutableStateOf(entry.category.takeIf { it != CategoryRepository.INCOME }) }
    var description by rememberSaveable { mutableStateOf(entry.description) }
    var date by rememberSaveable { mutableLongStateOf(entry.date.toEpochDay()) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val amount = digits.toLongOrNull()?.takeIf { it > 0 }
    val filed = if (kind == TxType.INCOME) CategoryRepository.INCOME else category
    val canSave = amount != null && filed != null && description.isNotBlank()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.voice_edit_title),
                style = type.size22.extraBold.tracked(-0.02f),
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TxType.EXPENSE to R.string.manual_expense, TxType.INCOME to R.string.manual_income).forEach { (option, label) ->
                    Chip(stringResource(label), selected = kind == option) { kind = option }
                }
            }
            val transformation = remember(state.myanmarDigits) { KyatGroupingTransformation(state.myanmarDigits) }
            KitTextField(
                value = digits,
                onValueChange = { text -> typedKyatDigits(text)?.let { digits = it } },
                label = stringResource(R.string.manual_amount),
                textStyle = type.size22.extraBold,
                trailing = { Text("Ks", style = type.size17.medium, color = colors.muted, modifier = Modifier.padding(end = 18.dp)) },
                visualTransformation = transformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            if (kind == TxType.EXPENSE) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldLabel(stringResource(R.string.manual_category))
                    FlowRow(
                        Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val names = (state.categories + listOfNotNull(entry.category.takeIf { it != CategoryRepository.INCOME })).distinct()
                        names.forEach { name -> Chip(categoryLabel(name), selected = category == name) { category = name } }
                    }
                }
            }
            KitTextField(
                value = description,
                onValueChange = { description = it },
                label = stringResource(R.string.manual_description),
                textStyle = type.size16.forContent(description),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldLabel(stringResource(R.string.manual_date))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.ground)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.manual_date_change)) { pickingDate = true }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Lucide.Calendar, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
                    Text(
                        dayLabel(LocalDate.ofEpochDay(date), state.today, state.myanmarDigits),
                        style = type.size16,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Lucide.ChevronDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
                }
            }
            PrimaryButton(
                text = stringResource(R.string.voice_done),
                onClick = {
                    val ok = amount ?: return@PrimaryButton
                    val name = filed ?: return@PrimaryButton
                    onDone(entry.copy(type = kind, amountKyat = ok, category = name, description = description.trim(), date = LocalDate.ofEpochDay(date)))
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            )
            CardLink(stringResource(R.string.voice_remove_entry), colors.coralText, onRemove)
        }
    }
    if (pickingDate) {
        DateSheet(
            date = LocalDate.ofEpochDay(date),
            today = state.today,
            myanmarDigits = state.myanmarDigits,
            onPick = {
                date = it.toEpochDay()
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }
}

/** A choice pill: sage with a check when chosen, an outline otherwise. One 48 dp target. */
@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(colors.sageFill) else Modifier.border(1.dp, colors.outline, CircleShape))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = if (selected) 12.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) Icon(Lucide.CheckBold, contentDescription = null, tint = colors.onSageFill, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = (if (selected) type.size15.semibold else type.size15.medium).forContent(text),
            color = if (selected) colors.onSageFill else colors.ink,
            maxLines = 1,
        )
    }
}

/**
 * The answer to a spoken question: what was asked, the period's spending (or the month's budgets) as the big
 * number, then by category or the entries themselves.
 */
@Composable
internal fun AnswerScreen(state: VoiceUiState, phase: VoicePhase.Answer, actions: VoiceActions, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val answer = phase.answer
    val question = answer.question
    val budgetQuestion = question.metric == VoiceMetric.OVER_BUDGET
    // A question about one category already lists its entries; a budget question is about categories.
    val canToggle = !budgetQuestion && question.category == null && answer.entries.isNotEmpty()
    val showEntries = !budgetQuestion && (phase.showEntries || question.category != null)
    VoicePage(
        title = answerTitle(question, state.today),
        onClose = actions.onClose,
        modifier = modifier,
        footer = {
            MicLink(stringResource(R.string.voice_ask_again), actions.onSayAgain)
            if (canToggle) {
                SecondaryButton(
                    text = stringResource(if (showEntries) R.string.voice_see_categories else R.string.voice_see_entries),
                    onClick = actions.onToggleAnswerEntries,
                    height = 56.dp,
                    contentColor = colors.accent,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
    ) {
        if (phase.heard.isNotBlank()) {
            SectionLabel(stringResource(R.string.voice_you_asked), Modifier.padding(bottom = 10.dp))
            HeardQuote(phase.heard)
        }
        if (budgetQuestion) {
            SectionLabel(stringResource(R.string.voice_budgets_label, monthName(YearMonth.from(answer.to))), Modifier.padding(top = 24.dp))
            val over = answer.overCount
            Text(
                if (over > 0) pluralStringResource(R.plurals.voice_over_count, over, over) else stringResource(R.string.voice_within_budgets),
                style = type.size26.extraBold.tracked(-0.02f),
                color = if (over > 0) colors.coralText else colors.ink,
                modifier = Modifier.padding(top = 6.dp),
            )
            SageRule(Modifier.padding(top = 16.dp))
        } else {
            SectionLabel(spentLabel(answer.from, answer.to, state), Modifier.padding(top = 24.dp))
            BigKyat(
                figure = groupDigits(answer.totalKyat, state.myanmarDigits),
                spoken = stringResource(R.string.kyat_amount, groupDigits(answer.totalKyat)),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val listLabel = if (showEntries) R.string.voice_these_entries else R.string.voice_by_category
        SectionLabel(stringResource(listLabel), Modifier.padding(top = 28.dp, bottom = 10.dp))
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        when {
            budgetQuestion && answer.byCategory.isEmpty() -> Quiet(stringResource(R.string.voice_no_budgets))
            !budgetQuestion && answer.entries.isEmpty() -> Quiet(stringResource(R.string.voice_nothing_spent))
            showEntries -> answer.entries.forEach { row ->
                SpentRow(row, state)
                HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            }
            else -> {
                val largest = answer.byCategory.maxOfOrNull { it.amountKyat }?.coerceAtLeast(1) ?: 1
                answer.byCategory.forEach { total ->
                    CategoryRow(total, largest, state.myanmarDigits)
                    HorizontalDivider(thickness = 1.dp, color = colors.hairline)
                }
            }
        }
    }
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = BudgetWiseTheme.type.size15, color = BudgetWiseTheme.colors.muted, modifier = Modifier.padding(vertical = 16.dp))
}

/** A category's spending with a bar against the largest one, or against its budget (coral when over). */
@Composable
private fun CategoryRow(total: CategoryTotal, largest: Long, myanmarDigits: Boolean) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val limit = total.limitKyat
    val share = if (limit != null) total.amountKyat.toFloat() / limit.coerceAtLeast(1) else total.amountKyat.toFloat() / largest
    val bar: Color = if (total.over) colors.coral else categoryColors(total.category).content
    Row(
        Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CategoryBadge(total.category, contentDescription = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(categoryLabel(total.category), style = type.size16.medium.forContent(total.category), color = colors.ink, maxLines = 1)
            Box(Modifier.width(180.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.raised)) {
                Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(bar))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatKyat(total.amountKyat, myanmarDigits),
                style = type.size15.semibold,
                color = if (total.over) colors.coralText else colors.ink,
                maxLines = 1,
            )
            if (limit != null) {
                Text(stringResource(R.string.voice_of_limit, formatKyat(limit, myanmarDigits)), style = type.size13, color = colors.muted, maxLines = 1)
            }
        }
    }
}

/** One expense in the period: what, its category and day, and the amount. */
@Composable
private fun SpentRow(row: Transaction, state: VoiceUiState) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(
        Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CategoryBadge(row.category, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(row.description, style = type.size16.medium.forContent(row.description), color = colors.ink, maxLines = 1)
            Text(
                "${categoryLabel(row.category)} · ${dayLabel(row.date, state.today, state.myanmarDigits)}",
                style = type.size13,
                color = colors.muted,
                maxLines = 1,
            )
        }
        Text(formatKyat(row.amountKyat, state.myanmarDigits), style = type.size15.semibold, color = colors.ink, maxLines = 1)
    }
}

/** "This week", "Today", "This month", "Budgets"; else a plain "Your spending". */
@Composable
private fun answerTitle(question: VoiceQuestion, today: LocalDate): String {
    if (question.metric == VoiceMetric.OVER_BUDGET) return stringResource(R.string.nav_budgets)
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return stringResource(
        when {
            question.from == today && question.to == today -> R.string.transactions_today
            question.from == today.minusDays(1) && question.to == today.minusDays(1) -> R.string.transactions_yesterday
            question.from == monday && question.to == today -> R.string.voice_this_week
            question.from == today.withDayOfMonth(1) && question.to == today -> R.string.voice_this_month
            else -> R.string.voice_your_spending
        },
    )
}

/** "Spent, Monday Oct 26 to today", or "Spent, Yesterday" for one day. */
@Composable
private fun spentLabel(from: LocalDate, to: LocalDate, state: VoiceUiState): String {
    if (from == to) return stringResource(R.string.voice_spent_on, dayLabel(from, state.today, state.myanmarDigits))
    val start = localizedDate(from, if (from.year == state.today.year) "EEEEMMMd" else "EEEEMMMdy", state.myanmarDigits)
    val end = if (to == state.today) stringResource(R.string.voice_today_lower) else localizedDate(to, "MMMd", state.myanmarDigits)
    return stringResource(R.string.voice_spent_range, start, end)
}

@Preview(showBackground = true, heightDp = 844)
@Composable
private fun ReviewPreview() {
    val today = LocalDate.parse("2026-10-30")
    BudgetWiseTheme {
        VoiceScreen(
            VoiceUiState(
                today,
                VoicePhase.Review(
                    ReviewState(
                        heard = "ကော်ဖီ သုံးထောင်၊ တက္ကစီ ငါးထောင်",
                        entries = listOf(
                            VoiceEntry(TxType.EXPENSE, 3000, "Food and Drink Item", "ကော်ဖီ", today, dateGuessed = true),
                            VoiceEntry(TxType.EXPENSE, 5000, "Transport", "တက္ကစီ", today, dateGuessed = true),
                        ),
                    ),
                ),
            ),
            VoiceActions(),
        )
    }
}
