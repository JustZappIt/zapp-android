// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * What funding a gift card commits the sender to.
 *
 * These four facts are the ones with no undo — there is no reclaim, the link is the money, and a
 * lost device before the hand-off is a lost card. Printed under the review screen they read as a
 * wall of warnings above the one button that matters; a tap away they stay findable without
 * standing between the sender and their own decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GiftCardFundingInfoSheet(onDismiss: () -> Unit) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    ZashiScreenModalBottomSheet(onDismissRequest = onDismiss) { padding ->
        Column(
            modifier =
                Modifier.padding(
                    start = spacing.xl3,
                    end = spacing.xl3,
                    bottom = padding.calculateBottomPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            BasicText(
                text = stringResource(R.string.gift_card_review_warning_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            InfoPoint(R.string.gift_card_review_warning_irreversible)
            InfoPoint(R.string.gift_card_review_warning_bearer)
            InfoPoint(R.string.gift_card_review_warning_backup)
            InfoPoint(R.string.gift_card_review_warning_delay)
            ZappButton(
                text = stringResource(co.electriccoin.zcash.ui.design.R.string.general_ok),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InfoPoint(
    @androidx.annotation.StringRes body: Int,
) {
    BasicText(
        text = stringResource(body),
        style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
    )
}
