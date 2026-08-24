// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.theme.ZappTheme

@Composable
internal fun GiftClaimBottomBar(state: GiftClaimState) {
    val spacing = ZappTheme.spacing
    val action = state.primaryAction()
    ZappBottomActionBar(
        onBack = state.onBack,
        isBackEnabled = state.isBackEnabled,
        primaryAction =
            action?.let { giftAction ->
                {
                    ZappButton(
                        text = stringResource(giftAction.textRes),
                        onClick = giftAction.onClick,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(start = spacing.lg),
                    )
                }
            },
    )
}

private data class GiftClaimAction(
    @param:StringRes val textRes: Int,
    val onClick: () -> Unit,
)

private fun GiftClaimState.primaryAction(): GiftClaimAction? =
    when (stage) {
        GiftClaimStage.PREVIEW -> {
            previewAction()
        }

        GiftClaimStage.NEEDS_WALLET -> {
            GiftClaimAction(R.string.gift_claim_needs_wallet_action, onCreateWallet)
        }

        GiftClaimStage.CONSENT -> {
            GiftClaimAction(R.string.gift_claim_consent_confirm, onConsent)
        }

        GiftClaimStage.DONE,
        GiftClaimStage.ALREADY_CLAIMED,
        -> {
            GiftClaimAction(R.string.gift_claim_done, onBack)
        }

        GiftClaimStage.AWAITING_FUNDING -> {
            GiftClaimAction(R.string.gift_claim_retry, onClaim)
        }

        // Nothing is required of the recipient here — back leaves, and the claim confirms without
        // them. This exists because the one thing this screen cannot see is a claim that will never
        // mine: it was broadcast by the card's wallet, so an unmined one and a dead one look
        // identical from here. Without a way to re-check, that would be a screen with no way out.
        GiftClaimStage.CLAIM_CONFIRMING -> {
            GiftClaimAction(R.string.gift_claim_confirming_recheck, onClaim)
        }

        GiftClaimStage.CLAIMING -> {
            if (canStopClaim) {
                GiftClaimAction(R.string.gift_claim_stop_scan, onStopClaim)
            } else {
                null
            }
        }

        // Nothing to press while reading the link, and nothing to do while confirmations accrue
        // but wait — that screen re-checks itself instead of asking the recipient to keep tapping.
        GiftClaimStage.LOADING,
        GiftClaimStage.PENDING_CONFIRMATIONS,
        -> {
            null
        }
    }

/** Without a loaded card, this action remains a way forward rather than becoming a no-op. */
private fun GiftClaimState.previewAction(): GiftClaimAction =
    when {
        isLoaded -> GiftClaimAction(R.string.gift_claim_claim, onClaim)
        isRetryable -> GiftClaimAction(R.string.gift_claim_retry, onRetry)
        else -> GiftClaimAction(R.string.gift_claim_done, onBack)
    }
