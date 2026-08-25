// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

/**
 * How every Zapp screen sizes the primary action inside `ZappBottomActionBar`: it takes the width
 * the back arrow leaves, rather than hugging its own text. Kept here so the voting screens cannot
 * drift from ChooseServer, ChatSettings, PortfolioChartSettings and the rest.
 */
fun RowScope.voteBarAction(): Modifier = Modifier.weight(1f).padding(start = BAR_ACTION_GAP)

private val BAR_ACTION_GAP = 12.dp
