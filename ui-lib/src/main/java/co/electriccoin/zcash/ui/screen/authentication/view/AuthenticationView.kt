package co.electriccoin.zcash.ui.screen.authentication.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationResult
import co.electriccoin.zcash.ui.design.component.AppAlertDialog
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.splash.ZappZBackground

const val APP_ACCESS_AUTH_TEST_TAG = "APP_ACCESS_AUTH_TEST_TAG"

@Preview("App Access Authentication")
@Composable
private fun PreviewAppAccessAuthentication() {
    ZcashTheme(forceDarkMode = false) {
        BlankSurface {
            AppAccessAuthentication(
                onRetry = {},
                showAuthLogo = false,
            )
        }
    }
}

@Preview("App Access Authentication - Failed")
@Composable
private fun PreviewAppAccessAuthenticationFailed() {
    ZcashTheme(forceDarkMode = false) {
        BlankSurface {
            AppAccessAuthentication(
                onRetry = {},
                showAuthLogo = true,
            )
        }
    }
}

/**
 * App-access privacy screen: the brand Z fills the screen behind the system biometric / PIN
 * prompt. When a previous attempt failed ([showAuthLogo]), it surfaces a manual lock tap-target
 * so the user is never stranded. The caller owns automatic retry to ensure only one authentication
 * request is launched at a time.
 */
@Composable
fun AppAccessAuthentication(
    onRetry: (() -> Unit),
    showAuthLogo: Boolean,
    modifier: Modifier = Modifier,
) {
    ProvideZappTheme {
        Box(modifier.fillMaxSize().testTag(APP_ACCESS_AUTH_TEST_TAG)) {
            ZappZBackground()

            if (showAuthLogo) {
                AppAccessAuthRetry(
                    onRetry = onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun AppAccessAuthRetry(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val unlockDesc =
        stringResource(
            id = R.string.authentication_failed_welcome_icon_cont_desc,
            stringResource(R.string.app_name),
        )

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .background(c.text, RectangleShape)
                    .clickable(onClick = onRetry)
                    .semantics { contentDescription = unlockDesc },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "🔒",
                style =
                    ZappTheme.typography.display.copy(
                        color = c.accent,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                    ),
            )
        }

        Spacer(Modifier.height(20.dp))

        BasicText(
            text = stringResource(id = R.string.authentication_failed_welcome_title),
            style =
                ZappTheme.typography.display.copy(
                    color = c.text,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.4).sp,
                    textAlign = TextAlign.Center,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = stringResource(id = R.string.authentication_failed_welcome_subtitle),
            style =
                ZappTheme.typography.body.copy(
                    color = c.text,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview
@Composable
private fun ErrorAuthenticationPreview() {
    ZcashTheme(forceDarkMode = false) {
        BlankSurface {
            AuthenticationErrorDialog(
                onDismiss = {},
                onRetry = {},
                onSupport = {},
                reason = AuthenticationResult.Error(errorCode = -1, errorMessage = "Test Error Message")
            )
        }
    }
}

@Preview
@Composable
private fun ErrorAuthenticationDarkPreview() {
    ZcashTheme(forceDarkMode = true) {
        BlankSurface {
            AuthenticationErrorDialog(
                onDismiss = {},
                onRetry = {},
                onSupport = {},
                reason = AuthenticationResult.Error(errorCode = -1, errorMessage = "Test Error Message")
            )
        }
    }
}

@Composable
fun AuthenticationErrorDialog(
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSupport: () -> Unit,
    reason: AuthenticationResult.Error
) {
    AppAlertDialog(
        title = stringResource(id = R.string.authentication_error_title),
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(id = R.string.authentication_error_text),
                    color = ZcashTheme.colors.textPrimary,
                )

                Spacer(modifier = Modifier.height(ZcashTheme.dimens.spacingDefault))

                Text(
                    text =
                        stringResource(
                            id = R.string.authentication_error_details,
                            reason.errorCode,
                            reason.errorMessage,
                        ),
                    fontStyle = FontStyle.Italic,
                    color = ZcashTheme.colors.textPrimary,
                )
            }
        },
        confirmButtonText = stringResource(id = R.string.authentication_error_button_retry),
        onConfirmButtonClick = onRetry,
        dismissButtonText = stringResource(id = R.string.authentication_error_button_support),
        onDismissButtonClick = onSupport,
        onDismissRequest = onDismiss,
    )
}

// Currently unused, we keep it for further iterations
@Composable
fun AuthenticationFailedDialog(
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSupport: () -> Unit
) {
    AppAlertDialog(
        title = stringResource(id = R.string.authentication_failed_title),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(id = R.string.authentication_failed_text),
                    color = ZcashTheme.colors.textPrimary,
                )
            }
        },
        confirmButtonText = stringResource(id = R.string.authentication_failed_button_retry),
        onConfirmButtonClick = onRetry,
        dismissButtonText = stringResource(id = R.string.authentication_failed_button_support),
        onDismissButtonClick = onSupport,
        onDismissRequest = onDismiss,
    )
}
