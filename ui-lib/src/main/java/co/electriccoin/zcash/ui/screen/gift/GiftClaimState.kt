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

    /**
     * The card reads, but this device has no wallet to claim it into. Shows what it is worth all
     * the same: "create a wallet" has to arrive as an offer attached to a real amount, not as a
     * demand made before the recipient has been told what they were sent.
     */
    NEEDS_WALLET,

    /** The card is understood: show what it is worth and let them decide. */
    PREVIEW,

    /** The card is old enough that scanning back to it needs explicit agreement (§3.6). */
    CONSENT,

    /** The card's own wallet is syncing, then spending. */
    CLAIMING,

    /** The funds are in this wallet. */
    DONE,

    /**
     * This wallet already broadcast a claim for this card and it has not reached finality yet.
     *
     * Its own stage because the alternative is [PREVIEW], and offering "Claim" over a claim already
     * in flight is the app forgetting what it did a minute ago: a rescan there can only rediscover
     * the transaction already recorded on this device, and offline it fails with a network error
     * over money that has already moved.
     */
    CLAIM_CONFIRMING,

    /**
     * The money is on the card but has not got its ten confirmations yet. Deliberately not [EMPTY]:
     * telling someone a perfectly good card is fake is the single worst failure this screen has.
     */
    PENDING_CONFIRMATIONS,

    /** The card's funding has not reached its shielded balance yet. Safe to check again later. */
    AWAITING_FUNDING,

    /** A different holder spent this bearer card before this wallet did. */
    ALREADY_CLAIMED,
}

internal enum class GiftClaimError {
    MALFORMED_LINK,
    WRONG_NETWORK,
    BIRTHDAY_ABOVE_TIP,

    /**
     * A real gift written by a newer build — a version this one cannot read, or v1 carrying fields
     * it does not know. The card is fine and the money is still on it, so the ask is an update, not
     * a new link from the sender.
     */
    NEWER_FORMAT,

    /** Zapp has not reached the chain tip yet, so the card cannot be judged. Retry, do not blame the card. */
    WALLET_NOT_READY,

    /**
     * There is no link behind this screen — the store refused it, or the process died holding the
     * claim. Nothing reached the card either way, so nothing was lost.
     */
    LINK_UNAVAILABLE,

    /** The transfer may or may not have reached the network. The card is untouched either way. */
    NOT_BROADCAST,

    /**
     * The card cannot cover the fee to move its own funds. Nothing the recipient does fixes it, so
     * the copy must not read as a wait — but the money is still on the card, so it must not read as
     * a loss either.
     */
    UNDERFUNDED,

    /** The card's server could not be reached, so the scan never started. Nothing is wrong with the card. */
    UNREACHABLE,

    /**
     * The card's server answered, but this phone could not finish the search and the scan stopped
     * advancing. Kept apart from [UNREACHABLE] because the connection is fine: what the recipient
     * can do is free the phone up or move to another one, not check their signal.
     */
    SCAN_STALLED,

    /**
     * The Sapling proving parameters are missing and could not be downloaded, so no shielded
     * transaction can be built at all. Kept out of [FAILED] because "try again" is wrong advice:
     * the scan already found the money and nothing changes until the download succeeds.
     */
    PARAMS_UNAVAILABLE,
    FAILED,
}

internal data class GiftClaimState(
    val stage: GiftClaimStage,
    val amount: Zatoshi?,
    /** What the card is worth in the wallet's chosen currency. Null when there is no rate to use. */
    val fiat: StringResource?,
    val message: String?,
    val expiry: GiftExpiryDisplay?,
    val blocksToScan: Long?,
    val progressFraction: Float?,
    val blocksRemaining: Long?,
    val confirmations: Int?,
    val requiredConfirmations: Int,
    /** True only while the isolated wallet is scanning and no transaction is being built/submitted. */
    val canStopClaim: Boolean,
    val error: GiftClaimError?,
    val onClaim: () -> Unit,
    val onConsent: () -> Unit,
    val onRetry: () -> Unit,
    val onCreateWallet: () -> Unit,
    val onStopClaim: () -> Unit,
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

    /** A link we never held cannot be reloaded from here — it has to be opened again where it arrived. */
    val isRetryable: Boolean
        get() = error != GiftClaimError.LINK_UNAVAILABLE

    /**
     * Never above the target. The count keeps climbing after the threshold is met — finalization
     * takes a moment longer than the tenth block — and "13 / 10 confirmations" reads as a bug.
     */
    val confirmationsShown: Int?
        get() = confirmations?.coerceAtMost(requiredConfirmations)

    /** 0..1 across the confirmations a freshly funded card still owes, when they are known. */
    val confirmationFraction: Float?
        get() =
            confirmations?.let {
                if (requiredConfirmations <= 0) null else (it.toFloat() / requiredConfirmations).coerceIn(0f, 1f)
            }

    val amountText: StringResource?
        get() = amount?.let { giftAmountRes(it) }
}
