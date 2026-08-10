package co.electriccoin.zcash.ui.common.security

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.screen.onboarding.view.PinVerifyScreen

@Composable
internal fun PinVerifyOverlay(state: PinVerifyState) {
    PinVerifyScreen(
        hasError = state.hasError,
        lockoutSecondsRemaining = state.lockoutSecondsRemaining,
        onPinSubmit = state.onPinSubmit,
        onCancel = state.onCancel,
    )
}
