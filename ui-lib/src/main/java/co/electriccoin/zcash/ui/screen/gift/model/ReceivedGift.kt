// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlinx.serialization.Serializable

/**
 * A gift this wallet collected.
 *
 * Deliberately carries **no mnemonic**. Once a card is claimed its funds are ordinary wallet funds,
 * so keeping the bearer secret afterwards would be retaining a key that unlocks nothing and leaks
 * everything. This is a receipt, not a recovery path — losing it costs nothing but the record.
 *
 * [address] is the identity: a card is one ephemeral address, and claiming the same link twice must
 * not produce two receipts.
 */
@Serializable
data class ReceivedGift(
    val address: String,
    val network: String,
    val amountZatoshi: Long,
    val claimedAt: String,
    val claimTxids: List<String>,
    val message: String? = null,
) {
    // The message is the sender's words and the amount is money; neither belongs in a log line.
    override fun toString(): String = "ReceivedGift(network=$network, redacted)"
}

/** Newest first, and one receipt per card however many times its link is opened. */
internal fun List<ReceivedGift>.recording(gift: ReceivedGift): List<ReceivedGift> =
    listOf(gift) + filterNot { it.address == gift.address }
