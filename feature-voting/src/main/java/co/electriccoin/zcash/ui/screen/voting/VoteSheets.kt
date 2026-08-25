// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationState
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationStyle
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState

/**
 * The voting view models are upstream's, so they describe their sheets as [ZashiConfirmationState].
 * The fork's own sheet is [ZappConfirmationBottomSheet] — the one the swap, offramp and P2P screens
 * use — so this is the single place that converts, and no voting screen renders the Zashi sheet.
 *
 * Zapp's sheet carries no icon, which is the intended loss: these sheets say what happened in words
 * and the icon was decoration.
 */
@Composable
fun VoteConfirmationBottomSheet(state: ZashiConfirmationState?) =
    ZappConfirmationBottomSheet(state = state?.toZappConfirmation())

private fun ZashiConfirmationState.toZappConfirmation(): ZappConfirmationState {
    val isWarning = style == ZashiConfirmationStyle.UNVERIFIED_POLL_WARNING
    return ZappConfirmationState(
        title = title,
        message = message,
        // A warning leads with the cautious action rather than the confirming one, which is the
        // whole reason the style exists; Zapp's sheet renders primary first, so they swap.
        primaryButton = if (isWarning) secondaryAction ?: primaryAction else primaryAction,
        secondaryButton = if (isWarning) primaryAction else secondaryAction,
        isDestructive = isWarning,
        onBack = onBack,
    )
}
