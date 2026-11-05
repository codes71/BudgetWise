package app.budgetwise.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.data.repo.AccountDeletionResult
import app.budgetwise.ui.common.KitTextField
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SecondaryButton
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.semibold

private const val PRIVACY_URL = "https://budgetwis3.vercel.app/privacy"

@Composable
fun AccountPrivacySection(hasAccount: Boolean, viewModel: AccountDeletionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var linkFailed by remember { mutableStateOf(false) }
    AccountPrivacyControls(
        hasAccount = hasAccount,
        onPrivacy = { try { uri.openUri(PRIVACY_URL); linkFailed = false } catch (_: Exception) { linkFailed = true } },
        onDelete = viewModel::open,
    )
    if (linkFailed) Text(
        stringResource(R.string.account_privacy_link_failed, PRIVACY_URL),
        style = BudgetWiseTheme.type.size13,
        color = BudgetWiseTheme.colors.coralText,
    )
    if (state.accountId != null) AccountDeletionDialog(state, viewModel::password, viewModel::confirm, viewModel::dismiss)
}

@Composable
fun AccountPrivacyControls(hasAccount: Boolean, onPrivacy: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.account_privacy_section), Modifier.semantics { heading() })
        SecondaryButton(stringResource(R.string.account_privacy_policy), onPrivacy, Modifier.fillMaxWidth())
        if (hasAccount) TextAction(
            stringResource(R.string.account_delete), onDelete, Modifier.fillMaxWidth(), color = BudgetWiseTheme.colors.coralText,
        )
    }
}

/** Password stays in the view model's memory, never saved state. Busy deletion cannot be dismissed or repeated. */
@Composable
fun AccountDeletionDialog(
    state: AccountDeletionUiState,
    onPassword: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Dialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
    ) {
        // A finite width before measuring the password field also avoids wrap-content dialog width feedback when
        // long failure copy changes the scrollable content. The outer inset works at narrow widths and large text.
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(), color = colors.ground, shape = RoundedCornerShape(24.dp)) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val title = stringResource(R.string.account_delete_title)
                Text(title, style = type.size20.semibold.forContent(title), color = colors.ink, modifier = Modifier.semantics { heading() })
                val detail = stringResource(R.string.account_delete_detail, state.email)
                Text(detail, style = type.size14.forContent(detail), color = colors.muted)
                val unsynced = stringResource(R.string.account_delete_unsynced)
                Text(unsynced, style = type.size14.forContent(unsynced), color = colors.coralText)
                KitTextField(
                    value = state.password,
                    onValueChange = onPassword,
                    label = stringResource(R.string.account_delete_password),
                    modifier = Modifier.fillMaxWidth(),
                    fieldModifier = Modifier.semantics { contentType = ContentType.Password },
                    enabled = !state.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    error = if (state.failure == AccountDeletionResult.WrongPassword) stringResource(R.string.account_delete_wrong_password) else null,
                )
                state.failure?.takeUnless { it == AccountDeletionResult.WrongPassword }?.let {
                    val failure = stringResource(deletionFailure(it))
                    Text(failure, style = type.size14.forContent(failure), color = colors.coralText)
                }
                if (state.busy) PrimaryButton(
                    stringResource(R.string.account_deleting), {}, Modifier.fillMaxWidth(), busy = true, height = 48.dp,
                ) else SecondaryButton(
                    stringResource(R.string.account_delete_confirm), onConfirm, Modifier.fillMaxWidth(),
                    enabled = state.password.isNotEmpty(), contentColor = colors.coralText,
                )
                SecondaryButton(
                    stringResource(R.string.account_delete_keep), onDismiss, Modifier.fillMaxWidth(), enabled = !state.busy,
                    contentColor = colors.ink,
                )
            }
        }
        }
    }
}

private fun deletionFailure(result: AccountDeletionResult): Int = when (result) {
    AccountDeletionResult.NeedsInternet -> R.string.account_delete_offline
    AccountDeletionResult.AccountChanged -> R.string.account_delete_account_changed
    AccountDeletionResult.DeletionIncomplete -> R.string.account_delete_incomplete
    AccountDeletionResult.LocalCleanupFailed -> R.string.account_delete_local_failed
    else -> R.string.account_delete_failed
}
