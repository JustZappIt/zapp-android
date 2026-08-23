// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R

@StringRes
internal fun GiftCardStage.subtitleRes(): Int =
    when (this) {
        GiftCardStage.DETAILS -> R.string.gift_card_subtitle_details
        GiftCardStage.PREPARING, GiftCardStage.REVIEW -> R.string.gift_card_subtitle_review
        GiftCardStage.FUNDING -> R.string.gift_card_subtitle_funding
        GiftCardStage.READY -> R.string.gift_card_subtitle_ready
        GiftCardStage.UNAVAILABLE -> R.string.gift_card_subtitle_unavailable
    }

// A lookup table, not a decision: one arm per case, no nesting, and exhaustive so a new error
// cannot be added without landing here. Same reasoning as `SubmitResultFold`.
@Suppress("CyclomaticComplexMethod")
@StringRes
internal fun GiftCardError.messageRes(): Int =
    when (this) {
        GiftCardError.AMOUNT_INVALID -> R.string.gift_card_amount_error_invalid
        GiftCardError.MESSAGE_TOO_LONG -> R.string.gift_card_message_error_too_long
        GiftCardError.INSUFFICIENT_FUNDS -> R.string.gift_card_error_insufficient
        GiftCardError.KEYSTONE_UNSUPPORTED -> R.string.gift_card_error_keystone
        GiftCardError.UNSUPPORTED_NETWORK -> R.string.gift_card_error_network
        GiftCardError.CHAIN_TIP_UNAVAILABLE -> R.string.gift_card_error_chain_tip
        GiftCardError.PERSIST_FAILED -> R.string.gift_card_error_persist
        GiftCardError.MINT_FAILED -> R.string.gift_card_error_mint
        GiftCardError.PROPOSAL_FAILED -> R.string.gift_card_error_proposal
        GiftCardError.AUTHENTICATION_FAILED -> R.string.gift_card_error_auth
        GiftCardError.SUBMIT_UNCERTAIN -> R.string.gift_card_error_submit_uncertain
        GiftCardError.SHARE_FAILED -> R.string.gift_card_error_share
    }
