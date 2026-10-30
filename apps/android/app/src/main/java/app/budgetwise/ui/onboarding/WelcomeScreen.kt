package app.budgetwise.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.ui.common.BrandMark
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SageRule
import app.budgetwise.ui.common.SecondaryButton
import app.budgetwise.ui.common.localizedDate
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.tracked
import app.budgetwise.ui.transactions.AxisLabels
import java.time.LocalDate

/**
 * The first screen without an account (first launch, and after signing out): the brand, a month of spending bars, what
 * the app does, and the two ways in. Both buttons stay pinned at the bottom.
 */
@Composable
fun WelcomeScreen(
    onCreateAccount: () -> Unit,
    onSignIn: () -> Unit,
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
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp),
        ) {
            Row(
                Modifier.padding(top = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BrandMark(size = 40.dp)
                Text(stringResource(R.string.app_name), style = type.size17.bold.tracked(-0.01f), color = colors.ink)
            }
            MonthBars(today, Modifier.padding(top = 72.dp))
            val word = stringResource(R.string.welcome_headline_word)
            val headline = stringResource(R.string.welcome_headline, word)
            Text(
                text = buildAnnotatedString {
                    append(headline)
                    val at = headline.indexOf(word)
                    if (at >= 0) addStyle(SpanStyle(color = colors.accent), at, at + word.length)
                },
                style = type.size40.extraBold.tracked(-0.03f),
                color = colors.ink,
                modifier = Modifier.padding(top = 44.dp).semantics { heading() },
            )
            SageRule(Modifier.padding(top = 20.dp))
            Text(
                stringResource(R.string.welcome_body),
                style = type.size15,
                color = colors.muted,
                modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(stringResource(R.string.welcome_create), onCreateAccount, Modifier.fillMaxWidth())
            SecondaryButton(
                text = stringResource(R.string.welcome_sign_in),
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp,
                contentColor = colors.ink,
            )
        }
    }
}

/** A made-up month of daily spending (the kit's), as bar heights in dp; 0 is a day with nothing spent. */
private val SampleDays = listOf(
    56, 24, 80, 16, 0, 40, 32, 112, 24, 48, 0, 16, 40, 72, 32, 144, 48, 24, 8, 0, 56, 96, 32, 64, 40, 88, 20, 52, 0, 36, 60,
)

/**
 * The idea of the app in one picture: a bar per day of this month up to today (today's in sage), stubs for the days to
 * come, over Day 1, Today and the last day. Decorative: the headline says it in words.
 */
@Composable
private fun MonthBars(today: LocalDate, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val days = today.lengthOfMonth()
    val todayIndex = today.dayOfMonth - 1
    Column(modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Row(
            Modifier.fillMaxWidth().height(160.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            repeat(days) { i ->
                val sample = SampleDays[i % SampleDays.size]
                val bar = when {
                    i > todayIndex || sample == 0 -> Modifier.height(3.dp).background(colors.raised)
                    else -> Modifier
                        .height(sample.dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (i == todayIndex) colors.accent else colors.coral)
                }
                Box(Modifier.width(6.dp).then(bar))
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        AxisLabels(
            start = localizedDate(today.withDayOfMonth(1), "MMMd"),
            today = stringResource(R.string.transactions_today),
            end = localizedDate(today.withDayOfMonth(days), "MMMd"),
            todayFraction = (todayIndex + 0.5f) / days,
            todayColor = colors.accent,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    BudgetWiseTheme { WelcomeScreen(onCreateAccount = {}, onSignIn = {}, today = LocalDate.parse("2026-10-23")) }
}
