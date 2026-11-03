package app.budgetwise.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.budgetwise.R
import app.budgetwise.currentAppLanguage
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.BrandMark
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.formatSignedKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import java.time.LocalDate
import java.time.YearMonth

/**
 * The first screen without an account (after the language, and after signing out from a fresh start): the brand
 * with the language chip, an example of the number the app keeps (what is left this month, with the entries that
 * made it), what the app does, Get started, and sign in for those who have an account. The buttons stay pinned.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onSignIn: () -> Unit,
    onLanguage: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = remember { LocalDate.now() },
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(
        modifier
            .fillMaxSize()
            .background(colors.ground)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BrandMark(size = 32.dp)
                Text(stringResource(R.string.app_name), style = type.size18.bold.tracked(-0.01f), color = colors.ink)
                Spacer(Modifier.weight(1f))
                LanguageChip(onLanguage)
            }
            Text(
                text = stringResource(R.string.draft3_welcome_headline),
                style = if (type.burmese) type.size32.bold else type.size40.bold.copy(fontSize = 38.sp, lineHeight = 43.sp).tracked(-0.045f),
                color = colors.ink,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp).semantics { heading() },
            )
            Text(
                stringResource(R.string.draft3_welcome_body),
                style = type.size16,
                color = colors.muted,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
            )
            SampleMonth(today)
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.draft3_welcome_offline), style = type.size14.medium, color = colors.ink)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(stringResource(R.string.welcome_get_started), onGetStarted, Modifier.fillMaxWidth())
            AuthFooterLink(stringResource(R.string.welcome_have_account), stringResource(R.string.login_submit), onSignIn)
        }
    }
}

/** The app's language in its own script, in an outlined pill; opens the language screen. One 48 dp target. */
@Composable
private fun LanguageChip(onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val language = currentAppLanguage()
    val name = language.ownName()
    val spoken = "${stringResource(R.string.settings_language)}, $name"
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(name, style = scriptStyle(language, 14).medium, color = colors.ink, maxLines = 1)
            Icon(Lucide.ChevronDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** Decorative monthly example. The two visible expenses are part of the month’s total spending. */
private const val SampleSalary = 850_000L
private const val SampleMonthlySpend = 186_400L
private const val SampleTea = 3_500L
private const val SampleBus = 600L

/**
 * A decorative month card below the headline, with the approved shader still and two sample entries.
 * The full month's remaining balance includes spending outside these two visible examples.
 */
@Composable
private fun SampleMonth(today: LocalDate, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val month = monthName(YearMonth.from(today))
    Column(modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surface).clearAndSetSemantics { }) {
        Image(
            painter = painterResource(if (colors.isDark) R.drawable.draft3_welcome_mesh_dark else R.drawable.draft3_welcome_mesh_light),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(80.dp).background(colors.surface),
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.transactions_left_in, month), style = type.size13, color = colors.muted)
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = groupDigits(SampleSalary - SampleMonthlySpend),
                    style = type.size32.bold.tracked(-0.035f),
                    color = colors.ink,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline(),
                )
                Text("Ks", style = type.size18.medium, color = colors.muted, modifier = Modifier.alignByBaseline())
            }
            Column(Modifier.padding(top = 12.dp)) {
                SampleRow(stringResource(R.string.welcome_sample_tea), SampleTea, TxType.EXPENSE)
                HorizontalDivider(thickness = 1.dp, color = colors.hairline)
                SampleRow(stringResource(R.string.welcome_sample_bus), SampleBus, TxType.EXPENSE)
            }
        }
    }
}

/** A list row as Transactions draws it, without the swipe. */
@Composable
private fun SampleRow(description: String, amount: Long, txType: TxType) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(description, style = type.size14.medium.forContent(description), color = colors.ink)
        }
        Text(
            text = formatSignedKyat(amount, txType),
            style = type.size14.semibold,
            color = if (txType == TxType.INCOME) colors.accent else colors.ink,
            softWrap = false,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    BudgetWiseTheme { WelcomeScreen(onGetStarted = {}, onSignIn = {}, onLanguage = {}, today = LocalDate.parse("2026-10-23")) }
}
