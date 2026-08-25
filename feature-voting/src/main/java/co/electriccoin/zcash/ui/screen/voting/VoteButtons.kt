// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.util.getValue

/**
 * The voting view models are carried over from upstream unchanged, so they still describe their
 * buttons as [ButtonState]. This is the single place that turns one into the fork's button, so no
 * voting screen has to know about the Zashi button at all.
 */
@Composable
fun VoteButton(
    state: ButtonState,
    modifier: Modifier = Modifier,
    variant: ZappButtonVariant = state.style.toZappVariant(),
) = ZappButton(
    text = state.text.getValue(),
    modifier = modifier,
    variant = variant,
    enabled = state.isEnabled,
    loading = state.isLoading,
    onClick = state.onClick,
)

/** TERTIARY is upstream's quiet, borderless button; the fork's nearest equal is Ghost. */
fun ButtonStyle?.toZappVariant(): ZappButtonVariant =
    when (this) {
        ButtonStyle.SECONDARY -> ZappButtonVariant.Secondary
        ButtonStyle.TERTIARY -> ZappButtonVariant.Ghost
        ButtonStyle.DESTRUCTIVE1, ButtonStyle.DESTRUCTIVE2 -> ZappButtonVariant.Danger
        ButtonStyle.PRIMARY, null -> ZappButtonVariant.Primary
    }
