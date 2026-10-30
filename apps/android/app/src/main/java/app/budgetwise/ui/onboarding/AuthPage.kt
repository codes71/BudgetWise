package app.budgetwise.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.BrandMark
import app.budgetwise.ui.common.SageRule
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked

/**
 * Sign in and create account: the back arrow (or room for it), the big title over the sage rule, the subtitle, the
 * [fields], then [button] pinned at the bottom, above the keyboard when it is open. [footer] (the link to the other
 * form) shows only while the keyboard is down, so it never sits where the next field was.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AuthPage(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    button: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    showMark: Boolean = false,
    fields: @Composable ColumnScope.() -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(
        modifier
            .fillMaxSize()
            .background(colors.ground)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        if (onBack != null) {
            BackHeader(title = null, onBack = onBack)
        } else {
            Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(64.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        ) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                if (showMark) BrandMark(Modifier.padding(bottom = 20.dp), size = 40.dp)
                Text(
                    text = title,
                    style = type.size32.bold.tracked(-0.02f),
                    color = colors.ink,
                    modifier = Modifier.semantics { heading() },
                )
                SageRule(Modifier.padding(top = 14.dp))
                Text(subtitle, style = type.size15, color = colors.muted, modifier = Modifier.padding(top = 16.dp))
                Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp), content = fields)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            button()
            if (!WindowInsets.isImeVisible) footer() else Spacer(Modifier.height(12.dp))
        }
    }
}

/** "Already have an account? Sign in": muted words and a sage link, one 48 dp target. */
@Composable
internal fun AuthFooterLink(prompt: String, link: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(prompt, style = type.size14, color = colors.muted)
        Text(link, style = type.size14.semibold, color = colors.accent)
    }
}
