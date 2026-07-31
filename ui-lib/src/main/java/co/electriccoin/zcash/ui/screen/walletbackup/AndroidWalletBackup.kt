package co.electriccoin.zcash.ui.screen.walletbackup

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.onboarding.view.PinVerifyScreen
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun AndroidWalletBackup(args: WalletBackup) {
    val viewModel = koinViewModel<WalletBackupViewModel> { parametersOf(args) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pinVerifyState by viewModel.pinVerifyState.collectAsStateWithLifecycle()

    BackHandler {
        if (pinVerifyState != WalletBackupViewModel.PinVerifyState.Idle) {
            viewModel.onPinEntryDismissed()
        } else {
            state?.onBack?.invoke()
        }
    }

    if (pinVerifyState != WalletBackupViewModel.PinVerifyState.Idle) {
        PinVerifyScreen(
            hasError = pinVerifyState == WalletBackupViewModel.PinVerifyState.Error,
            lockoutSecondsRemaining =
                (pinVerifyState as? WalletBackupViewModel.PinVerifyState.Locked)?.secondsRemaining ?: 0,
            onPinSubmit = { pin -> viewModel.onPinSubmitted(pin) },
            onCancel = { viewModel.onPinEntryDismissed() }
        )
    } else {
        state?.let { WalletBackupView(state = it) }
    }
}

@Serializable
data class WalletBackup(
    val isOpenedFromSeedBackupInfo: Boolean
)
