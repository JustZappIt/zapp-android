// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.voting.VoteTrustIndicator
import co.electriccoin.zcash.ui.design.R as DesignR

/**
 * Who vouches for a poll. Endorsement carries the wallet mark at full colour; an unverified poll
 * is deliberately quieter — muted text, no fill — so trust reads as the exception, not the badge.
 */
@Composable
fun VoteTrustIndicatorView(
    indicator: VoteTrustIndicator,
    modifier: Modifier = Modifier
) {
    val c = ZappTheme.colors
    val labelRes =
        when (indicator) {
            VoteTrustIndicator.ZODL -> R.string.coinVote_pollsList_approvedByZodl
            VoteTrustIndicator.UNVERIFIED -> R.string.coinVote_pollsList_unverifiedSheetTitle
        }
    val iconRes =
        when (indicator) {
            VoteTrustIndicator.ZODL -> DesignR.drawable.ic_item_zashi
            VoteTrustIndicator.UNVERIFIED -> DesignR.drawable.ic_info
        }
    val tint =
        when (indicator) {
            VoteTrustIndicator.ZODL -> c.text
            VoteTrustIndicator.UNVERIFIED -> c.textMuted
        }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.sm)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        BasicText(
            text = stringResource(labelRes),
            style = ZappTheme.typography.caption.copy(color = tint)
        )
    }
}
