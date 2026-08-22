// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlinx.serialization.Serializable

/**
 * A gift this wallet collected. A receipt, not a recovery path — losing it costs nothing but the
 * record.
 *
 * Carries **no mnemonic**: once a card is claimed its funds are ordinary wallet funds, so keeping
 * the bearer secret would retain a key that unlocks nothing and leaks everything. [address] is the
 * identity, so claiming the same link twice cannot produce two receipts.
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
    // The sender's words and an amount; neither belongs in a log line.
    override fun toString(): String = "ReceivedGift(network=$network, redacted)"
}

/** Newest first, and one receipt per card however many times its link is opened. */
internal fun List<ReceivedGift>.recording(gift: ReceivedGift): List<ReceivedGift> =
    listOf(gift) + filterNot { it.address == gift.address }
