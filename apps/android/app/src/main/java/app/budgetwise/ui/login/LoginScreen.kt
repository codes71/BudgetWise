package app.budgetwise.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.BuildConfig
import app.budgetwise.R
import app.budgetwise.ui.common.BrandMark
import app.budgetwise.ui.theme.BudgetWiseTheme

/** [onClose] is set when sign-in was opened on top of the app (expired session), so the user can go back. */
@Composable
fun LoginRoute(
    onSignedIn: () -> Unit,
    onClose: (() -> Unit)?,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signedIn by rememberUpdatedState(onSignedIn)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it == LoginEvent.SignedIn) signedIn() }
    }
    LoginScreen(
        state = state,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::submit,
        onConfirmDiscard = viewModel::confirmDiscard,
        onDismissDiscard = viewModel::dismissDiscard,
        onClose = onClose,
        serverUrl = if (BuildConfig.DEBUG) BuildConfig.API_BASE_URL else null,
    )
}

@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
    onClose: (() -> Unit)?,
    serverUrl: String?,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val passwordFocus = remember { FocusRequester() }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val submit = {
        keyboard?.hide()
        onSubmit()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BrandMark(size = 72.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(if (state.returning) R.string.login_subtitle_returning else R.string.login_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(36.dp))

                    val wrong = state.error == LoginError.WrongCredentials
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = onEmailChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
                        enabled = !state.loading,
                        label = { Text(stringResource(R.string.login_email)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_mail), contentDescription = null) },
                        isError = wrong,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFocus)
                            .semantics { contentType = ContentType.Password },
                        enabled = !state.loading,
                        label = { Text(stringResource(R.string.login_password)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    painter = painterResource(if (showPassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                                    contentDescription = stringResource(
                                        if (showPassword) R.string.login_hide_password else R.string.login_show_password,
                                    ),
                                )
                            }
                        },
                        isError = wrong,
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) submit() }),
                    )

                    state.error?.let { LoginErrorText(it, Modifier.fillMaxWidth().padding(top = 16.dp)) }

                    Spacer(Modifier.height(24.dp))
                    // Disabled while signing in, so TalkBack says it cannot be pressed again, but kept in its brand
                    // color (the spinner says it is busy) instead of the grey of an incomplete form.
                    val colors = MaterialTheme.colorScheme
                    Button(
                        onClick = submit,
                        enabled = state.canSubmit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        colors = if (state.loading) {
                            ButtonDefaults.buttonColors(disabledContainerColor = colors.primary, disabledContentColor = colors.onPrimary)
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.login_signing_in), style = MaterialTheme.typography.labelLarge)
                        } else {
                            Text(stringResource(R.string.login_submit), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (onClose != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onClose, enabled = !state.loading) {
                            Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (serverUrl != null) {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.server_url, serverUrl),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    state.discardPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onDismissDiscard,
            iconContentColor = MaterialTheme.colorScheme.error,
            icon = { Icon(painterResource(R.drawable.ic_error), contentDescription = null) },
            title = { Text(stringResource(R.string.login_discard_title), style = MaterialTheme.typography.headlineSmall) },
            text = {
                val n = prompt.unsyncedCount
                Text(
                    text = if (prompt.previousEmail != null) {
                        pluralStringResource(R.plurals.login_discard_message, n, n, prompt.previousEmail)
                    } else {
                        pluralStringResource(R.plurals.login_discard_message_unknown, n, n)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmDiscard,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.login_discard_confirm), style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscard) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
private fun LoginErrorText(error: LoginError, modifier: Modifier = Modifier) {
    val message = when (error) {
        LoginError.WrongCredentials -> stringResource(R.string.login_error_wrong_credentials)
        is LoginError.RateLimited -> error.minutes?.let {
            pluralStringResource(R.plurals.login_error_rate_limited_minutes, it, it)
        } ?: stringResource(R.string.login_error_rate_limited)
        LoginError.Network -> stringResource(R.string.login_error_network)
        LoginError.Server -> stringResource(R.string.login_error_server)
        LoginError.Generic -> stringResource(R.string.login_error_generic)
    }
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_error),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    BudgetWiseTheme {
        LoginScreen(
            state = LoginUiState(email = "aye@example.com", error = LoginError.WrongCredentials),
            onEmailChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onConfirmDiscard = {},
            onDismissDiscard = {},
            onClose = null,
            serverUrl = "http://127.0.0.1:9002/api/v1/",
        )
    }
}
