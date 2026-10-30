package app.budgetwise.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.ui.common.FieldHint
import app.budgetwise.ui.common.KitTextField
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.Notice
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.theme.BudgetWiseTheme

/** [onSignIn] opens sign-in instead (the footer link, or "Sign in instead" under a taken email). */
@Composable
fun CreateAccountRoute(
    onCreated: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    viewModel: CreateAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val created by rememberUpdatedState(onCreated)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it == CreateAccountEvent.SignedIn) created() }
    }
    CreateAccountScreen(
        state = state,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::submit,
        onSignIn = onSignIn,
        onBack = onBack,
    )
}

@Composable
fun CreateAccountScreen(
    state: CreateAccountUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val passwordFocus = remember { FocusRequester() }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val submit = {
        keyboard?.hide()
        onSubmit()
    }
    // While the account is being made the fields are dimmed and locked; the button says it is busy.
    val fieldsAlpha = if (state.loading) 0.6f else 1f
    val editable = !state.loading

    AuthPage(
        title = stringResource(R.string.create_title),
        subtitle = stringResource(R.string.create_subtitle),
        onBack = onBack,
        modifier = modifier,
        step = Step(at = 1, of = 2),
        button = {
            PrimaryButton(
                text = stringResource(if (state.loading) R.string.create_creating else R.string.create_submit),
                onClick = submit,
                enabled = state.canSubmit,
                busy = state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        footer = {
            AuthFooterLink(stringResource(R.string.create_have_account), stringResource(R.string.login_submit), onSignIn, enabled = editable)
        },
    ) {
        KitTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = stringResource(R.string.login_email),
            modifier = Modifier.alpha(fieldsAlpha),
            fieldModifier = Modifier.semantics { contentType = ContentType.NewUsername + ContentType.EmailAddress },
            leading = Lucide.Mail,
            placeholder = stringResource(R.string.login_email_hint),
            error = when (state.error) {
                CreateAccountError.EmailTaken -> stringResource(R.string.create_error_email_taken)
                CreateAccountError.InvalidEmail -> stringResource(R.string.create_error_invalid_email)
                else -> null
            },
            errorAction = if (state.error == CreateAccountError.EmailTaken) {
                { TextAction(stringResource(R.string.create_sign_in_instead), onSignIn) }
            } else {
                null
            },
            enabled = editable,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
        )
        KitTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.login_password),
            modifier = Modifier.alpha(fieldsAlpha),
            fieldModifier = Modifier.focusRequester(passwordFocus).semantics { contentType = ContentType.NewPassword },
            leading = Lucide.Lock,
            placeholder = stringResource(R.string.create_password_hint),
            error = if (state.error == CreateAccountError.ShortPassword) stringResource(R.string.create_error_short_password) else null,
            enabled = editable,
            trailing = {
                IconButton(onClick = { showPassword = !showPassword }, enabled = editable, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (showPassword) Lucide.EyeOff else Lucide.Eye,
                        contentDescription = stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password),
                        tint = colors.muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) submit() }),
            below = { FieldHint(stringResource(R.string.create_password_rule)) },
        )
        CreateAccountNotice(state.error)
    }
}

/** What went wrong away from the fields, where it happened: under them, above the button. */
@Composable
private fun CreateAccountNotice(error: CreateAccountError?) {
    when (error) {
        CreateAccountError.NeedsInternet -> Notice(
            tone = NoticeTone.INFO,
            icon = Lucide.CloudOff,
            title = stringResource(R.string.login_error_needs_internet_title),
            detail = stringResource(R.string.create_error_needs_internet),
        )
        CreateAccountError.RateLimited -> Notice(
            tone = NoticeTone.ATTENTION,
            icon = Lucide.Clock,
            title = stringResource(R.string.login_error_rate_limited_title),
            detail = stringResource(R.string.login_error_rate_limited),
        )
        CreateAccountError.Server -> Notice(
            tone = NoticeTone.ERROR,
            icon = Lucide.CloudAlert,
            title = stringResource(R.string.login_error_server_title),
            detail = stringResource(R.string.login_error_server),
        )
        CreateAccountError.Generic -> Notice(
            tone = NoticeTone.ERROR,
            icon = Lucide.CircleAlert,
            title = stringResource(R.string.login_error_generic_title),
            detail = stringResource(R.string.login_error_generic),
        )
        CreateAccountError.EmailTaken, CreateAccountError.InvalidEmail, CreateAccountError.ShortPassword, null -> Unit
    }
}

@Preview(showBackground = true)
@Composable
private fun CreateAccountScreenPreview() {
    BudgetWiseTheme {
        CreateAccountScreen(
            state = CreateAccountUiState(email = "aye.chan@example.com", error = CreateAccountError.EmailTaken),
            onEmailChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onSignIn = {},
            onBack = {},
        )
    }
}
