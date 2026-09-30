// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import co.electriccoin.zcash.ui.screen.onboarding.view.PinVerifyScreen

// Over the screen that asked for it: back cancels it, and no touch reaches the screen underneath.
@Composable
internal fun PinVerifyOverlay(state: PinVerifyState) {
    BackHandler(onBack = state.onCancel)
    Box(
        modifier =
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        PinVerifyScreen(
            hasError = state.hasError,
            lockoutSecondsRemaining = state.lockoutSecondsRemaining,
            onPinSubmit = state.onPinSubmit,
            onCancel = state.onCancel,
        )
    }
}
