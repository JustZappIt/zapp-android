// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlinx.serialization.Serializable

/**
 * A gift this wallet is collecting, and — until its claim is final — the only way back to it.
 *
 * A broadcast that reached the mempool can expire or reorg. The link is written before broadcast,
 * kept with the isolated wallet database, and dropped only after SDK finality.
 * [address] is the identity, so one link cannot produce two receipts.
 */
@Serializable
data class ReceivedGift(
    val address: String,
    val network: String,
    val amountZatoshi: Long,
    val claimedAt: String,
    val destinationAddress: String? = null,
    /** Account that received the claim, persisted so confirmation never follows UI selection. */
    val destinationAccountUuid: String? = null,
    val claimTxids: List<String> = emptyList(),
    val message: String? = null,
    /** The bearer link, held until every [claimTxids] transaction reaches SDK finality. */
    val claimLink: GiftLinkPayload? = null,
    /** Durable cleanup checkpoint written before the isolated database is deleted. */
    val isFinalized: Boolean = false,
) {
    init {
        // A link for another network cannot retry this one. IAE so the store reads it as corrupt.
        require(claimLink == null || claimLink.network == network) {
            "Received gift link does not match its record"
        }
    }

    /** The claim is final, so nothing can need the link again. */
    val isSettled: Boolean
        get() = claimLink == null

    // The sender's words, an amount, and — while unsettled — the mnemonic.
    override fun toString(): String = "ReceivedGift(network=$network, redacted)"
}

/** Newest first, one receipt per card, and never regresses durable recovery state. */
internal fun List<ReceivedGift>.recording(gift: ReceivedGift): List<ReceivedGift> {
    val current = firstOrNull { it.address == gift.address }
    val merged =
        when {
            current == null -> {
                gift
            }

            current.isSettled -> {
                current
            }

            else -> {
                gift.copy(
                    destinationAddress = gift.destinationAddress ?: current.destinationAddress,
                    destinationAccountUuid = gift.destinationAccountUuid ?: current.destinationAccountUuid,
                    claimTxids = mergeClaimTxids(current.claimTxids, gift.claimTxids),
                    claimLink = current.claimLink ?: gift.claimLink,
                    isFinalized = current.isFinalized || gift.isFinalized,
                )
            }
        }
    return listOf(merged) + filterNot { it.address == gift.address }
}

private fun mergeClaimTxids(current: List<String>, incoming: List<String>): List<String> =
    when {
        incoming.isEmpty() -> current
        current.isEmpty() -> incoming
        incoming.any(current::contains) -> (current + incoming).distinct()
        else -> incoming
    }

/** Drops the link for [address]. One-way, and a no-op if absent. */
internal fun List<ReceivedGift>.settling(address: String): List<ReceivedGift> =
    map { if (it.address == address) it.copy(claimLink = null) else it }

internal fun List<ReceivedGift>.finalizing(address: String): List<ReceivedGift> =
    map { if (it.address == address) it.copy(isFinalized = true) else it }
