package app.budgetwise.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
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
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.BrandMark
import app.budgetwise.ui.common.KitTextField
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.Notice
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold

/**
 * [onClose] is set when sign-in was opened on top of the app (expired session), so the user can go back. A sign-in made
 * on the phone without the server lands on Transactions like any other; the header's chip says the entries wait.
 */
@Composable
fun LoginRoute(
    onSignedIn: () -> Unit,
    onClose: (() -> Unit)?,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signedIn by rememberUpdatedState(onSignedIn)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it is LoginEvent.SignedIn) signedIn() }
    }
    LoginScreen(
        state = state,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::submit,
        onClose = onClose,
    )
}

@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val keyboard = LocalSoftwareKeyboardController.current
    val passwordFocus = remember { FocusRequester() }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val submit = {
        keyboard?.hide()
        onSubmit()
    }
    // While signing in the fields are dimmed and locked; the button says it is busy.
    val fieldsAlpha = if (state.loading) 0.6f else 1f

    Column(
        modifier
            .fillMaxSize()
            .background(colors.ground)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        if (onClose != null) {
            BackHeader(title = null, onBack = onClose)
        } else {
            Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(56.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        ) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                BrandMark(size = 48.dp)
                Text(
                    text = stringResource(R.string.login_title),
                    style = type.size25.bold,
                    color = colors.ink,
                    modifier = Modifier.padding(top = 24.dp).semantics { heading() },
                )
                Text(
                    text = stringResource(if (state.returning) R.string.login_subtitle_returning else R.string.login_subtitle),
                    style = type.size15,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )

                val wrong = state.error == LoginError.WrongCredentials
                Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    KitTextField(
                        value = state.email,
                        onValueChange = onEmailChange,
                        label = stringResource(R.string.login_email),
                        modifier = Modifier.alpha(fieldsAlpha),
                        fieldModifier = Modifier.semantics { contentType = ContentType.Username + ContentType.EmailAddress },
                        leading = Lucide.Mail,
                        placeholder = stringResource(R.string.login_email_hint),
                        enabled = !state.loading,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    )
                    KitTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = stringResource(R.string.login_password),
                        modifier = Modifier.alpha(fieldsAlpha),
                        fieldModifier = Modifier.focusRequester(passwordFocus).semantics { contentType = ContentType.Password },
                        leading = Lucide.Lock,
                        placeholder = stringResource(R.string.login_password_hint),
                        error = if (wrong) stringResource(R.string.login_error_wrong_credentials) else null,
                        enabled = !state.loading,
                        trailing = {
                            IconButton(onClick = { showPassword = !showPassword }, enabled = !state.loading, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    imageVector = if (showPassword) Lucide.EyeOff else Lucide.Eye,
                                    contentDescription = stringResource(
                                        if (showPassword) R.string.login_hide_password else R.string.login_show_password,
                                    ),
                                    tint = colors.muted,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) submit() }),
                    )
                    // A wrong password is the field's own error; anything else is a notice where it happened.
                    state.error?.takeIf { !wrong }?.let { LoginNotice(it, Modifier.padding(top = 4.dp)) }
                    PrimaryButton(
                        text = stringResource(if (state.loading) R.string.login_signing_in else R.string.login_submit),
                        onClick = submit,
                        enabled = state.canSubmit,
                        busy = state.loading,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginNotice(error: LoginError, modifier: Modifier = Modifier) {
    when (error) {
        LoginError.WrongCredentials -> Unit
        LoginError.NeedsInternet -> Notice(
            tone = NoticeTone.INFO,
            icon = Lucide.CloudOff,
            title = stringResource(R.string.login_error_needs_internet_title),
            detail = stringResource(R.string.login_error_needs_internet),
            modifier = modifier,
        )
        is LoginError.RateLimited -> Notice(
            tone = NoticeTone.ATTENTION,
            icon = Lucide.Clock,
            title = stringResource(R.string.login_error_rate_limited_title),
            detail = error.minutes?.let { pluralStringResource(R.plurals.login_error_rate_limited_minutes, it, it) }
                ?: stringResource(R.string.login_error_rate_limited),
            modifier = modifier,
        )
        LoginError.Server -> Notice(
            tone = NoticeTone.ERROR,
            icon = Lucide.CloudAlert,
            title = stringResource(R.string.login_error_server_title),
            detail = stringResource(R.string.login_error_server),
            modifier = modifier,
        )
        LoginError.Generic -> Notice(
            tone = NoticeTone.ERROR,
            icon = Lucide.CircleAlert,
            title = stringResource(R.string.login_error_generic_title),
            detail = stringResource(R.string.login_error_generic),
            modifier = modifier,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    BudgetWiseTheme {
        LoginScreen(
            state = LoginUiState(email = "aye.chan@example.com", error = LoginError.WrongCredentials),
            onEmailChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onClose = null,
        )
    }
}
