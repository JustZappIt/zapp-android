// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlinx.serialization.Serializable

/**
 * A gift this wallet collected, and — until its claim has mined — the only way back to it.
 *
 * A broadcast that reached the mempool is not a claim that landed: it can expire unmined, and then
 * the card still holds its funds while the card's own wallet has already been deleted. So the link
 * is kept until the claim is on chain and dropped the moment it is. [address] is the identity, so
 * one link cannot produce two receipts.
 */
@Serializable
data class ReceivedGift(
    val address: String,
    val network: String,
    val amountZatoshi: Long,
    val claimedAt: String,
    val claimTxids: List<String>,
    val message: String? = null,
    /** The bearer link, held only until [claimTxids] are seen on chain. */
    val claimLink: GiftLinkPayload? = null,
) {
    init {
        // A link for another network cannot retry this one. IAE so the store reads it as corrupt.
        require(claimLink == null || claimLink.network == network) {
            "Received gift link does not match its record"
        }
    }

    /** The claim is on chain, so nothing can need the link again. */
    val isSettled: Boolean
        get() = claimLink == null

    // The sender's words, an amount, and — while unsettled — the mnemonic.
    override fun toString(): String = "ReceivedGift(network=$network, redacted)"
}

/** Newest first, and one receipt per card however many times its link is opened. */
internal fun List<ReceivedGift>.recording(gift: ReceivedGift): List<ReceivedGift> =
    listOf(gift) + filterNot { it.address == gift.address }

/** Drops the link for [address], its claim now being on chain. One-way, and a no-op if absent. */
internal fun List<ReceivedGift>.settling(address: String): List<ReceivedGift> =
    map { if (it.address == address) it.copy(claimLink = null) else it }
