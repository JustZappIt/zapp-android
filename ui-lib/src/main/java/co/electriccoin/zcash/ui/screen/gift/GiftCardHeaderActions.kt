// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * What the gift card header offers beside the title: the way back to a saved card, and — only on
 * the stage whose next tap makes them — the funding terms.
 *
 * Split from `GiftCardView` to stay under detekt's per-file function count, as
 * `GiftClaimOutcomeSections` is.
 */
@Composable
internal fun HeaderActions(state: GiftCardState, onShowInfo: () -> Unit) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    state.onOpenSavedCards?.let { onOpen ->
        ZappSectionLabel(
            text = stringResource(R.string.gift_card_list_open),
            color = c.accentText,
            modifier = Modifier.clickable(onClick = onOpen),
        )
    }
    if (state.stage == GiftCardStage.REVIEW) {
        val label = stringResource(R.string.gift_card_review_warning_title)
        Box(
            modifier =
                Modifier
                    .size(spacing.xl6)
                    .clickable(onClick = onShowInfo)
                    .semantics {
                        role = Role.Button
                        contentDescription = label
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = c.text)
        }
    }
}
