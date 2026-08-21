// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes

/** Where the recipient is in the claim flow. */
internal enum class GiftClaimStage {
    /** Reading and checking the link. Entirely offline. */
    LOADING,

    /** The card is understood: show what it is worth and let them decide. */
    PREVIEW,

    /** The card is old enough that scanning back to it needs explicit agreement (§3.6). */
    CONSENT,

    /** The card's own wallet is syncing, then spending. */
    CLAIMING,

    /** The funds are in this wallet. */
    DONE,

    /**
     * The money is on the card but has not got its ten confirmations yet. Deliberately not [EMPTY]:
     * telling someone a perfectly good card is fake is the single worst failure this screen has.
     */
    PENDING_CONFIRMATIONS,

    /** Nothing there — never funded, or already claimed by someone else holding the link. */
    EMPTY,
}

internal enum class GiftClaimError {
    MALFORMED_LINK,
    WRONG_NETWORK,
    TAMPERED,
    BIRTHDAY_ABOVE_TIP,

    /** Zapp has not reached the chain tip yet, so the card cannot be judged. Retry, do not blame the card. */
    WALLET_NOT_READY,

    /** The process died holding this claim. The link never reached the card, so nothing was lost. */
    LINK_EXPIRED,

    /** The transfer may or may not have reached the network. The card is untouched either way. */
    NOT_BROADCAST,
    FAILED,
}

internal data class GiftClaimState(
    val stage: GiftClaimStage,
    val amount: Zatoshi?,
    val message: String?,
    val blocksToScan: Long?,
    val progressFraction: Float?,
    val blocksRemaining: Long?,
    val confirmations: Int?,
    val requiredConfirmations: Int,
    val error: GiftClaimError?,
    val onClaim: () -> Unit,
    val onConsent: () -> Unit,
    val onRetry: () -> Unit,
    val onBack: () -> Unit,
) {
    /**
     * Leaving mid-claim would abandon a sync the recipient has already waited on, and the broadcast
     * itself is uncancellable by design, so the only stages that offer a way out are the ones where
     * nothing is in flight.
     */
    val isBackEnabled: Boolean
        get() = stage != GiftClaimStage.CLAIMING

    /**
     * Whether the link loaded at all. When it did not there is nothing to claim, so the primary
     * action has to be a retry rather than a dead "Claim" button.
     */
    val isLoaded: Boolean
        get() = amount != null

    /** An expired link cannot be reloaded from here — it has to be opened again where it arrived. */
    val isRetryable: Boolean
        get() = error != GiftClaimError.LINK_EXPIRED

    /** 0..1 across the confirmations a freshly funded card still owes, when they are known. */
    val confirmationFraction: Float?
        get() =
            confirmations?.let {
                if (requiredConfirmations <= 0) null else (it.toFloat() / requiredConfirmations).coerceIn(0f, 1f)
            }

    val amountText: StringResource?
        get() = amount?.let { stringRes(it) }
}
