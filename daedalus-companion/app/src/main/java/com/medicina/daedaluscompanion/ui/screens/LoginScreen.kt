package com.medicina.daedaluscompanion.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MoneyOff
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.auth.AuthError
import com.medicina.daedaluscompanion.auth.AuthRepository
import com.medicina.daedaluscompanion.auth.AuthResult
import kotlinx.coroutines.launch

private enum class Step { WELCOME, SIGN_IN, CREATE, CHECK_EMAIL }

private fun looksLikeEmail(s: String) = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(s)

/**
 * Sign-in, Wealthsimple-style: welcome (Sign in / Sign up) → email and password on one screen,
 * plus create account and password reset.
 */
@Composable
fun LoginScreen(auth: AuthRepository, linkError: AuthError?) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember(linkError) { mutableStateOf(linkError) }
    var checkEmailBody by rememberSaveable { mutableStateOf(R.string.login_check_reset) }
    // Research use is off unless the person chooses it (privacy by default; changeable in Profile).
    var allowResearch by rememberSaveable { mutableStateOf(false) }

    fun go(to: Step) { step = to; error = null; password = "" }
    fun run(block: suspend () -> AuthResult, onDone: (AuthResult) -> Unit = {}) {
        busy = true; error = null
        scope.launch {
            val r = block()
            busy = false
            if (r is AuthResult.Failure) error = r.error else onDone(r)
        }
    }

    val backTo = if (step == Step.WELCOME) null else Step.WELCOME
    BackHandler(enabled = backTo != null) { backTo?.let(::go) }

    if (step == Step.WELCOME) {
        WelcomeScreen(onSignIn = { go(Step.SIGN_IN) }, onSignUp = { go(Step.CREATE) }, onGuest = auth::continueAsGuest)
        return
    }

    AuthFrame(
        onBack = { backTo?.let(::go) },
        notConfigured = !auth.isConfigured,
        busy = busy,
    ) {
        when (step) {
            Step.WELCOME -> Unit
            Step.SIGN_IN -> {
                val canSignIn = looksLikeEmail(email) && password.isNotEmpty() && !busy
                EmailField(email, onChange = { email = it; error = null }, onDone = { focusManager.moveFocus(FocusDirection.Down) })
                PasswordField(password, R.string.login_password, onChange = { password = it; error = null }, onDone = {
                    if (canSignIn) run({ auth.signInWithEmail(email, password) })
                })
                ErrorText(error)
                // Sign-in doesn't say whether an email has an account (that would reveal who uses a
                // Parkinson's app), so after a failed attempt offer both ways forward.
                if (error == AuthError.INVALID_CREDENTIALS) NoAccountHint { go(Step.CREATE) }
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.Start).offset(x = (-12).dp),
                    onClick = {
                        if (!looksLikeEmail(email)) {
                            error = AuthError.INVALID_EMAIL
                        } else {
                            run({ auth.sendPasswordReset(email) }) {
                                checkEmailBody = R.string.login_check_reset; go(Step.CHECK_EMAIL)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.login_forgot), color = DesignTokens.SageDeep, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.weight(1f))
                PrimaryButton(R.string.login_sign_in, enabled = canSignIn) {
                    run({ auth.signInWithEmail(email, password) })
                }
            }

            Step.CREATE -> {
                Headline(R.string.login_create_title, R.string.login_create_subtitle)
                EmailField(email, onChange = { email = it; error = null }, onDone = {})
                PasswordField(
                    password, R.string.login_new_password, onChange = { password = it; error = null }, onDone = {},
                    supporting = R.string.login_password_rule,
                )
                DataPromise(allowResearch, onAllowResearchChange = { allowResearch = it })
                ErrorText(error)
                Spacer(Modifier.weight(1f))
                PrimaryButton(R.string.login_create, enabled = looksLikeEmail(email) && password.length >= 8 && !busy) {
                    run({ auth.signUpWithEmail(email, password, analysisOptOut = !allowResearch) }) { r ->
                        if (r == AuthResult.ConfirmEmail) { checkEmailBody = R.string.login_check_confirm; go(Step.CHECK_EMAIL) }
                    }
                }
            }

            Step.CHECK_EMAIL -> {
                Icon(Icons.Outlined.MarkEmailRead, null, tint = DesignTokens.Sage, modifier = Modifier.size(40.dp))
                Headline(R.string.login_check_title, null)
                Text(stringResource(checkEmailBody, email), fontSize = 16.sp, color = DesignTokens.Muted, lineHeight = 22.sp)
                Spacer(Modifier.weight(1f))
                PrimaryButton(R.string.login_back_to_login, enabled = true) { go(Step.SIGN_IN) }
            }
        }
    }
}

/** Opened from a password-reset email: pick a new password. */
@Composable
fun NewPasswordScreen(auth: AuthRepository) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AuthError?>(null) }
    AuthFrame(onBack = {}, showBack = false, notConfigured = false, busy = busy) {
        Headline(R.string.login_reset_title, R.string.login_reset_subtitle)
        PasswordField(
            password, R.string.login_new_password, onChange = { password = it; error = null }, onDone = {},
            supporting = R.string.login_password_rule,
        )
        ErrorText(error)
        Spacer(Modifier.weight(1f))
        PrimaryButton(R.string.login_reset_save, enabled = password.length >= 8 && !busy) {
            busy = true
            scope.launch {
                val r = auth.updatePassword(password)
                busy = false
                if (r is AuthResult.Failure) error = r.error
            }
        }
    }
}

/** After a failed sign-in: check the email, or create an account with it. */
@Composable
private fun NoAccountHint(onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(DesignTokens.SageTint, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.login_no_account_hint), fontSize = 14.sp, lineHeight = 19.sp, color = DesignTokens.Ink)
        OutlinedButton(
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, DesignTokens.SageDeep),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = DesignTokens.Surface, contentColor = DesignTokens.SageDeep),
        ) { Text(stringResource(R.string.login_create_account_button), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** First screen when signed out: the app name, with Sign in and Sign up at the bottom. */
@Composable
private fun WelcomeScreen(onSignIn: () -> Unit, onSignUp: () -> Unit, onGuest: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val bars = WindowCompat.getInsetsController(window, view)
        val oldStatus = window.statusBarColor
        val oldNav = window.navigationBarColor
        val oldLight = bars.isAppearanceLightStatusBars
        window.statusBarColor = DesignTokens.SageDeep.toArgb()
        window.navigationBarColor = DesignTokens.SageDeep.toArgb()
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false
        onDispose {
            window.statusBarColor = oldStatus
            window.navigationBarColor = oldNav
            bars.isAppearanceLightStatusBars = oldLight
            bars.isAppearanceLightNavigationBars = oldLight
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(DesignTokens.SageDeep)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.app_name),
            fontSize = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp, color = Color.White,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onSignUp,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Surface, contentColor = DesignTokens.SageDeep),
            elevation = null,
        ) { Text(stringResource(R.string.login_sign_up), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, DesignTokens.OnSageDeep),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        ) { Text(stringResource(R.string.login_sign_in_button), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = onGuest, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.login_guest), color = DesignTokens.OnSageDeep, fontSize = 13.sp)
        }
    }
}

/** How we treat health data, and the research opt-out, shown before an account is created. */
@Composable
private fun DataPromise(allowResearch: Boolean, onAllowResearchChange: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(DesignTokens.SageTint, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.data_promise_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
        PromiseLine(Icons.Outlined.Lock, R.string.data_promise_secure)
        PromiseLine(Icons.Outlined.MoneyOff, R.string.data_promise_not_sold)
        PromiseLine(Icons.Outlined.VerifiedUser, R.string.data_promise_owned)
        HorizontalDivider(color = DesignTokens.SageLine.copy(alpha = 0.5f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.data_research_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
                Text(stringResource(R.string.data_research_signup_sub), fontSize = 13.sp, lineHeight = 18.sp, color = DesignTokens.Muted)
            }
            SageSwitch(checked = allowResearch, onCheckedChange = onAllowResearchChange)
        }
    }
}

@Composable
private fun PromiseLine(icon: ImageVector, @StringRes text: Int) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = DesignTokens.SageDeep, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(stringResource(text), fontSize = 14.sp, lineHeight = 19.sp, color = DesignTokens.Ink)
    }
}

// --- Building blocks ---

@Composable
internal fun AuthFrame(
    onBack: () -> Unit,
    showBack: Boolean = true,
    notConfigured: Boolean,
    busy: Boolean,
    /** Setup progress (0–1) shown as a thin bar; null for the sign-in screens. */
    progress: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(DesignTokens.Ground)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.CenterStart) {
            if (showBack) {
                IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), tint = DesignTokens.Ink)
                }
            } else {
                Text(stringResource(R.string.app_name), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DesignTokens.SageDeep)
            }
        }
        when {
            busy -> LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = DesignTokens.Sage, trackColor = DesignTokens.Ground)
            progress != null -> LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = DesignTokens.SageDeep, trackColor = DesignTokens.SageTrack, strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
            else -> Spacer(Modifier.height(2.dp))
        }
        if (notConfigured) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.login_not_configured),
                style = CaptionStyle.copy(fontSize = 12.sp, color = DesignTokens.SageDeep),
                modifier = Modifier.fillMaxWidth().background(DesignTokens.SageTint, RoundedCornerShape(12.dp)).padding(12.dp),
            )
        }
        // Scrolls on small screens; on normal ones Spacer(weight) pushes the actions to the bottom.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val minHeight = maxHeight
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = minHeight).padding(top = 24.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun Headline(@StringRes title: Int, @StringRes subtitle: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, color = DesignTokens.Ink)
        if (subtitle != null) Text(stringResource(subtitle), fontSize = 16.sp, color = DesignTokens.Muted)
    }
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = DesignTokens.SageDeep,
    unfocusedBorderColor = DesignTokens.SwitchOff,
    focusedLabelColor = DesignTokens.SageDeep,
    cursorColor = DesignTokens.SageDeep,
    focusedContainerColor = DesignTokens.Surface,
    unfocusedContainerColor = DesignTokens.Surface,
)

@Composable
private fun EmailField(value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.trim()) },
        label = { Text(stringResource(R.string.login_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onDone() }),
        shape = RoundedCornerShape(16.dp), colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(value: String, @StringRes label: Int, onChange: (String) -> Unit, onDone: () -> Unit, @StringRes supporting: Int? = null) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(
                    if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    stringResource(if (show) R.string.login_hide_password else R.string.login_show_password),
                    tint = DesignTokens.Muted,
                )
            }
        },
        supportingText = supporting?.let { { Text(stringResource(it)) } },
        shape = RoundedCornerShape(16.dp), colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun PrimaryButton(@StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    PillButton(stringResource(label), onClick = onClick, enabled = enabled, height = 56.dp, modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun ErrorText(error: AuthError?) {
    if (error != null) Text(stringResource(errorText(error)), fontSize = 14.sp, color = DesignTokens.Alert)
}

@StringRes
private fun errorText(e: AuthError): Int = when (e) {
    AuthError.NOT_CONFIGURED -> R.string.login_err_not_configured
    AuthError.INVALID_CREDENTIALS -> R.string.login_err_credentials
    AuthError.EMAIL_NOT_CONFIRMED -> R.string.login_err_not_confirmed
    AuthError.USER_EXISTS -> R.string.login_err_exists
    AuthError.WEAK_PASSWORD -> R.string.login_err_weak
    AuthError.INVALID_EMAIL -> R.string.login_err_email
    AuthError.LINK_EXPIRED -> R.string.login_err_link_expired
    AuthError.NETWORK -> R.string.login_err_network
    AuthError.UNKNOWN -> R.string.login_err_unknown
}
