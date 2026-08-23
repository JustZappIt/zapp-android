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
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue

/** Fresh network pricing and an explicit confirmation before a retry can authenticate or spend. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GiftFundingRetrySheet(review: GiftFundingRetryReview) {
    val spacing = ZappTheme.spacing
    ZashiScreenModalBottomSheet(onDismissRequest = review.onDismiss) { padding ->
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
                text = stringResource(R.string.gift_card_retry_title),
                style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
            )
            BasicText(
                text = stringResource(R.string.gift_card_retry_body),
                style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
            )
            ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
                ZappSummaryRow(
                    label = stringResource(R.string.gift_card_review_amount),
                    value = review.amount.getValue(),
                )
                ZappSummaryRow(
                    label = stringResource(R.string.gift_card_review_reserve),
                    value = review.claimFeeReserve.getValue(),
                )
                ZappSummaryRow(
                    label = stringResource(R.string.gift_card_review_network_fee),
                    value = review.networkFee.getValue(),
                )
                ZappSummaryRow(
                    label = stringResource(R.string.gift_card_review_total),
                    value = review.total.getValue(),
                    valueColor = ZappTheme.colors.accentText,
                )
                review.message?.let {
                    ZappSummaryRow(label = stringResource(R.string.gift_card_review_message_label), value = it)
                }
            }
            ZappButton(
                text = stringResource(R.string.gift_card_retry_confirm),
                modifier = Modifier.fillMaxWidth(),
                onClick = review.onConfirm,
            )
            ZappButton(
                text = stringResource(R.string.gift_card_retry_cancel),
                modifier = Modifier.fillMaxWidth(),
                variant = ZappButtonVariant.Secondary,
                onClick = review.onDismiss,
            )
        }
    }
}
