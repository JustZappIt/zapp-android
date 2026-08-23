// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.usecase.GiftFundingQuote
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.gift.model.GiftMessage

/**
 * Where the sender is in the create flow.
 *
 * The order is the order they travel in, and only [DETAILS] and [REVIEW] are reversible: once
 * [FUNDING] has submitted there is no reclaim, so there is nothing to go back to.
 */
internal enum class GiftCardStage {
    DETAILS,
    PREPARING,
    REVIEW,
    FUNDING,
    READY,
}

private const val EXPIRY_WEEK_DAYS = 7
private const val EXPIRY_MONTH_DAYS = 30
private const val EXPIRY_QUARTER_DAYS = 90

/** How long a card suggests it stays claimable. Advisory — nothing on chain enforces it. */
internal enum class GiftExpiry(
    val days: Int?,
) {
    NEVER(null),
    WEEK(EXPIRY_WEEK_DAYS),
    MONTH(EXPIRY_MONTH_DAYS),
    QUARTER(EXPIRY_QUARTER_DAYS),
}

/** Everything the screen can tell the sender went wrong. */
internal enum class GiftCardError {
    AMOUNT_INVALID,
    MESSAGE_TOO_LONG,
    INSUFFICIENT_FUNDS,
    KEYSTONE_UNSUPPORTED,
    UNSUPPORTED_NETWORK,
    CHAIN_TIP_UNAVAILABLE,
    PERSIST_FAILED,
    MINT_FAILED,
    PROPOSAL_FAILED,
    AUTHENTICATION_FAILED,
    SUBMIT_REJECTED,

    /** Broadcast outcome unknown. The copy must not invite a retry — see `GiftFundingError`. */
    SUBMIT_UNCERTAIN,
    SHARE_FAILED,

    /**
     * The link is on the clipboard but the record of the hand-off did not save. The sender needs to
     * know: the card still counts as unshared, and that is what stands between it and a wallet
     * reset that would erase its only seed.
     */
    HANDOFF_FAILED,
}

internal data class GiftCardState(
    val stage: GiftCardStage,
    val amount: NumberTextFieldState,
    val spendableBalance: StringResource?,
    val message: String,
    val messageGraphemes: Int,
    val expiry: GiftExpiry,
    val quote: GiftFundingQuote?,
    /** The figure as typed, so the card can be drawn before there is a quote behind it. */
    val previewAmount: Zatoshi?,
    /** What the card is worth in the wallet's chosen currency. Null when there is no rate. */
    val fiat: StringResource?,
    val link: String?,
    val isCopied: Boolean,
    val isAuthenticating: Boolean,
    val error: GiftCardError?,
    val pinVerify: PinVerifyState?,
    val onAmountChange: (NumberTextFieldInnerState) -> Unit,
    val onMessageChange: (String) -> Unit,
    val onExpiryChange: (GiftExpiry) -> Unit,
    val onContinue: () -> Unit,
    val onConfirm: () -> Unit,
    val onCopy: () -> Unit,
    val onShare: (String) -> Unit,
    val onBack: () -> Unit,
    /** Null when nothing is stored, or when the stage has no way out. */
    val onOpenSavedCards: (() -> Unit)?,
) {
    /**
     * Whether the sender may leave. Funding is irreversible and the ready screen holds the only
     * copy of the link the sender has seen, so neither offers a way back.
     */
    val isBackEnabled: Boolean
        get() =
            when (stage) {
                GiftCardStage.DETAILS, GiftCardStage.REVIEW -> !isAuthenticating
                GiftCardStage.PREPARING, GiftCardStage.FUNDING, GiftCardStage.READY -> false
            }

    /**
     * [GiftCardError.SUBMIT_UNCERTAIN] latches funding off for good: the note may already be spent,
     * and a live button invites the retry its copy is asking the sender not to make. The way on is
     * the saved-cards list, where the card is waiting as unresolved.
     */
    val canConfirm: Boolean
        get() = !isAuthenticating && error != GiftCardError.SUBMIT_UNCERTAIN

    /**
     * Both message bounds, not just the counter's: a note can sit well under 128 clusters and still
     * blow the 512-byte limit, and the link codec would refuse to encode it.
     */
    val canContinue: Boolean
        get() =
            amount.innerState.amount != null &&
                !amount.isError &&
                (message.isEmpty() || GiftMessage.isWithinLimits(message))

    // The link is the money, and [quote] holds the ephemeral mnemonic. A generated toString would
    // drop both into any log line or crash report that interpolates the state.
    override fun toString(): String = "GiftCardState(stage=$stage, error=$error, redacted)"
}
