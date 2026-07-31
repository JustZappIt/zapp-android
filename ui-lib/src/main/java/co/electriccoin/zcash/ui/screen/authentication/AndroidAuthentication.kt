@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.authentication

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.di.koinActivityViewModel
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.MainActivity
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationResult
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationViewModel
import co.electriccoin.zcash.ui.preference.AuthMethod
import co.electriccoin.zcash.ui.screen.authentication.view.AppAccessAuthentication
import co.electriccoin.zcash.ui.screen.authentication.view.AuthenticationErrorDialog
import co.electriccoin.zcash.ui.screen.onboarding.view.PinVerifyScreen
import co.electriccoin.zcash.ui.util.EmailUtil
import kotlin.time.Duration.Companion.milliseconds

private const val APP_ACCESS_TRIGGER_DELAY = 0
private const val SEND_FUNDS_DELAY = 0
internal const val RETRY_TRIGGER_DELAY = 0

@Composable
internal fun MainActivity.WrapAuthentication(
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    onFail: () -> Unit,
    useCase: AuthenticationUseCase,
    authMethod: AuthMethod,
    goSupport: (() -> Unit)? = null,
) {
    WrapAuthenticationUseCases(
        activity = this,
        goSupport = goSupport,
        onSuccess = onSuccess,
        onCancel = onCancel,
        onFail = onFail,
        useCase = useCase,
        authMethod = authMethod,
    )
}

@Composable
@Suppress("LongParameterList")
private fun WrapAuthenticationUseCases(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    onFail: () -> Unit,
    useCase: AuthenticationUseCase,
    authMethod: AuthMethod,
    goSupport: (() -> Unit)? = null,
) {
    when (useCase) {
        AuthenticationUseCase.AppAccess -> {
            Twig.debug { "App Access Authentication" }
            WrapAppAccessAuth(
                activity = activity,
                goToAppContent = onSuccess,
                onCancel = onCancel,
                onFail = onFail,
                authMethod = authMethod,
            )
        }

        AuthenticationUseCase.SendFunds -> {
            Twig.debug { "Send Funds Authentication" }
            WrapSendFundsAuth(
                activity = activity,
                onSendFunds = onSuccess,
                goSupport = goSupport ?: {},
                onCancel = onCancel,
                onFail = onFail
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun WrapSendFundsAuth(
    activity: FragmentActivity,
    goSupport: () -> Unit,
    onSendFunds: () -> Unit,
    onCancel: () -> Unit,
    onFail: () -> Unit,
) {
    val authenticationViewModel = koinActivityViewModel<AuthenticationViewModel>()

    // Start authentication once, before the showPinEntry early-return, so this
    // LaunchedEffect stays in the composition tree while the PIN overlay is visible.
    LaunchedEffect(key1 = true) {
        authenticationViewModel.authenticate(
            activity = activity,
            initialAuthSystemWindowDelay = SEND_FUNDS_DELAY.milliseconds,
            useCase = AuthenticationUseCase.SendFunds
        )
    }

    val showPinEntry = authenticationViewModel.showPinEntry.collectAsStateWithLifecycle().value
    val pinEntryError = authenticationViewModel.pinEntryError.collectAsStateWithLifecycle().value
    val pinLockoutSecondsRemaining =
        authenticationViewModel.pinLockoutSecondsRemaining.collectAsStateWithLifecycle().value

    // PIN auth overlay — takes priority over the system biometric dialog.
    if (showPinEntry) {
        PinVerifyScreen(
            hasError = pinEntryError,
            lockoutSecondsRemaining = pinLockoutSecondsRemaining,
            onPinSubmit = { pin -> authenticationViewModel.submitPin(pin) },
            onCancel = {
                authenticationViewModel.cancelPinEntry()
                onCancel()
            }
        )
        return
    }

    val authenticationResult =
        authenticationViewModel.authenticationResult
            .collectAsStateWithLifecycle(initialValue = AuthenticationResult.None)
            .value

    when (authenticationResult) {
        AuthenticationResult.None -> {
            Twig.info { "Authentication result: initiating" }
            // Initial state
        }

        AuthenticationResult.Success -> {
            Twig.info { "Authentication result: successful" }
            authenticationViewModel.resetAuthenticationResult()
            onSendFunds()
        }

        AuthenticationResult.Canceled -> {
            Twig.info { "Authentication result: canceled" }
            authenticationViewModel.resetAuthenticationResult()
            onCancel()
        }

        AuthenticationResult.Failed -> {
            Twig.warn { "Authentication result: failed" }
            authenticationViewModel.resetAuthenticationResult()
            onFail()
            Toast
                .makeText(activity, stringResource(id = R.string.authentication_toast_failed), Toast.LENGTH_SHORT)
                .show()
        }

        is AuthenticationResult.Error -> {
            Twig.error {
                "Authentication result: error: ${authenticationResult.errorCode}: ${authenticationResult.errorMessage}"
            }
            AuthenticationErrorDialog(
                onDismiss = {
                    authenticationViewModel.resetAuthenticationResult()
                    onCancel()
                },
                onRetry = {
                    authenticationViewModel.resetAuthenticationResult()
                    authenticationViewModel.authenticate(
                        activity = activity,
                        initialAuthSystemWindowDelay = RETRY_TRIGGER_DELAY.milliseconds,
                        useCase = AuthenticationUseCase.SendFunds
                    )
                },
                onSupport = {
                    authenticationViewModel.resetAuthenticationResult()
                    goSupport()
                },
                reason = authenticationResult
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun WrapAppAccessAuth(
    activity: FragmentActivity,
    goToAppContent: () -> Unit,
    onCancel: () -> Unit,
    onFail: () -> Unit,
    authMethod: AuthMethod,
) {
    val authenticationViewModel = koinActivityViewModel<AuthenticationViewModel>()

    // PIN is already known when the required state is emitted, so its first frame can
    // be the PIN screen itself. Biometric auth still needs the system prompt setup.
    LaunchedEffect(authMethod) {
        if (authMethod != AuthMethod.PIN) {
            authenticationViewModel.authenticate(
                activity = activity,
                initialAuthSystemWindowDelay = APP_ACCESS_TRIGGER_DELAY.milliseconds,
                useCase = AuthenticationUseCase.AppAccess
            )
        }
    }

    val showPinEntry = authenticationViewModel.showPinEntry.collectAsStateWithLifecycle().value
    val pinEntryError = authenticationViewModel.pinEntryError.collectAsStateWithLifecycle().value
    val pinLockoutSecondsRemaining =
        authenticationViewModel.pinLockoutSecondsRemaining.collectAsStateWithLifecycle().value

    val authFailed = authenticationViewModel.authFailed.collectAsStateWithLifecycle().value

    val authenticationResult =
        authenticationViewModel.authenticationResult
            .collectAsStateWithLifecycle(initialValue = AuthenticationResult.None)
            .value

    var enteringApp by remember { mutableStateOf(false) }

    // PIN auth overlay — takes priority over the welcome animation.
    // No back button: app-open auth is mandatory and cannot be dismissed.
    val shouldShowPinEntry =
        showPinEntry ||
            (authMethod == AuthMethod.PIN && !enteringApp && authenticationResult != AuthenticationResult.Success)
    if (shouldShowPinEntry) {
        PinVerifyScreen(
            hasError = pinEntryError,
            showBack = false,
            lockoutSecondsRemaining = pinLockoutSecondsRemaining,
            onPinSubmit = { pin -> authenticationViewModel.submitPin(pin) },
        )
        return
    }

    // Auto-retry authentication when a previous attempt failed or was cancelled,
    // so the user never has to tap the lock icon manually.
    LaunchedEffect(authFailed) {
        if (authFailed) {
            authenticationViewModel.resetAuthenticationResult()
            authenticationViewModel.authenticate(
                activity = activity,
                initialAuthSystemWindowDelay = RETRY_TRIGGER_DELAY.milliseconds,
                useCase = AuthenticationUseCase.AppAccess
            )
        }
    }

    if (!enteringApp && authenticationResult != AuthenticationResult.Success) {
        AppAccessAuthentication(
            onRetry = {
                authenticationViewModel.resetAuthenticationResult()
                authenticationViewModel.authenticate(
                    activity = activity,
                    initialAuthSystemWindowDelay = RETRY_TRIGGER_DELAY.milliseconds,
                    useCase = AuthenticationUseCase.AppAccess
                )
            },
            showAuthLogo = authFailed,
        )
    }

    when (authenticationResult) {
        AuthenticationResult.None -> {
            Twig.debug { "Authentication result: initiating" }
            // Initial state
        }

        AuthenticationResult.Success -> {
            Twig.debug { "Authentication result: successful" }
            if (!enteringApp) enteringApp = true
            authenticationViewModel.resetAuthenticationResult()
            goToAppContent()
        }

        AuthenticationResult.Canceled -> {
            Twig.info { "Authentication result: canceled: shutting down" }
            authenticationViewModel.resetAuthenticationResult()
            Toast
                .makeText(activity, stringResource(id = R.string.authentication_toast_canceled), Toast.LENGTH_SHORT)
                .show()
            onCancel()
        }

        AuthenticationResult.Failed -> {
            Twig.warn { "Authentication result: failed" }
            onFail()
        }

        is AuthenticationResult.Error -> {
            Twig.error {
                "Authentication result: error: ${authenticationResult.errorCode}: ${authenticationResult.errorMessage}"
            }
            // Unrecoverable prompt errors (e.g. ERROR_NO_BIOMETRICS after the user removed
            // their fingerprints) would loop forever through the silent auto-retry above.
            // Show the explanatory dialog instead so the user can re-enrol and retry.
            val supportSubject = stringResource(R.string.app_name)
            val supportAddress = stringResource(R.string.support_email_address)
            AuthenticationErrorDialog(
                onDismiss = {
                    // App-open auth is mandatory; the dialog cannot be dismissed away.
                },
                onRetry = {
                    authenticationViewModel.resetAuthenticationResult()
                    authenticationViewModel.authenticate(
                        activity = activity,
                        initialAuthSystemWindowDelay = RETRY_TRIGGER_DELAY.milliseconds,
                        useCase = AuthenticationUseCase.AppAccess
                    )
                },
                onSupport = {
                    EmailUtil.sendEmailWithTextFallback(
                        context = activity,
                        recipientAddress = supportAddress,
                        subject = supportSubject,
                        messageBody =
                            "App access authentication error " +
                                "${authenticationResult.errorCode}: ${authenticationResult.errorMessage}"
                    )
                },
                reason = authenticationResult
            )
        }
    }
}

sealed class AuthenticationUseCase {
    data object AppAccess : AuthenticationUseCase()

    data object SendFunds : AuthenticationUseCase()
}
