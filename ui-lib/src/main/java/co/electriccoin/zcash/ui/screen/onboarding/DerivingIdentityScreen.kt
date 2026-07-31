package co.electriccoin.zcash.ui.screen.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.common.ChatBootstrap
import co.electriccoin.zcash.ui.screen.onboarding.view.RestoreInProgressScreen

@Composable
internal fun DerivingIdentityScreen(
    chatBootstrap: ChatBootstrap,
) {
    val chatIdentityFailed by chatBootstrap.chatIdentityFailed.collectAsStateWithLifecycle()
    val isDeriving by chatBootstrap.isDeriving.collectAsStateWithLifecycle()

    val errorMessage =
        if (chatIdentityFailed) stringResource(R.string.chat_identity_setup_error_wallet_derive_failed) else null
    val onRetry: (() -> Unit)? =
        if (chatIdentityFailed && !isDeriving) ({ chatBootstrap.retry() }) else null
    RestoreInProgressScreen(errorMessage = errorMessage, onRetry = onRetry)
}
