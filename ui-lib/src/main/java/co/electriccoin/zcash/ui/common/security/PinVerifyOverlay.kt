// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import co.electriccoin.zcash.ui.screen.onboarding.view.PinVerifyScreen

// Over the screen that asked for it: back cancels it, and being hit first keeps every touch from the screen underneath.
// Consuming touches as well would cancel any key tap during which the finger moves.
@Composable
internal fun PinVerifyOverlay(state: PinVerifyState) {
    BackHandler(onBack = state.onCancel)
    Box(modifier = Modifier.pointerInput(Unit) {}) {
        PinVerifyScreen(
            hasError = state.hasError,
            lockoutSecondsRemaining = state.lockoutSecondsRemaining,
            onPinSubmit = state.onPinSubmit,
            onCancel = state.onCancel,
        )
    }
}
