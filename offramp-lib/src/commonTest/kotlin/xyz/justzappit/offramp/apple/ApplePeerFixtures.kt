// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerDepositId
import xyz.justzappit.offramp.peer.PeerDepositStatus
import xyz.justzappit.offramp.peer.PeerFiat
import xyz.justzappit.offramp.peer.PeerIntent
import xyz.justzappit.offramp.peer.PeerIntentStatus
import xyz.justzappit.offramp.peer.PeerOrderSnapshot
import xyz.justzappit.offramp.peer.PeerPlatform

/** Builders shared by the Apple facade tests, so no single test carries a twenty-field literal. */
internal fun peerDepositId(onchain: String = "1"): PeerDepositId =
    PeerDepositId(escrowHex = ESCROW_HEX, onchain = onchain)

@Suppress("LongParameterList")
internal fun peerOrderSnapshot(
    remaining: Usdc6,
    outstanding: Usdc6 = Usdc6.ZERO,
    taken: Usdc6 = Usdc6.ZERO,
    withdrawn: Usdc6 = Usdc6.ZERO,
    acceptingIntents: Boolean = true,
    intents: List<PeerIntent> = emptyList(),
    platform: PeerPlatform = PeerPlatform.REVOLUT,
    onchain: String = "1",
    creationBlockNumber: Long? = null,
): PeerOrderSnapshot =
    PeerOrderSnapshot(
        id = peerDepositId(onchain),
        status = PeerDepositStatus.ACTIVE,
        acceptingIntents = acceptingIntents,
        remaining = remaining,
        outstandingIntentAmount = outstanding,
        totalAmountTaken = taken,
        totalWithdrawn = withdrawn,
        intentAmountMin = Usdc6.ZERO,
        intentAmountMax = remaining + outstanding + taken + withdrawn,
        signaledIntents = 0,
        fulfilledIntents = 0,
        prunedIntents = 0,
        platform = platform,
        payeeHash = null,
        currencies = emptyList(),
        intents = intents,
        creationTxHash = null,
        creationBlockNumber = creationBlockNumber,
        openedAtSeconds = null,
        lastActivityAtSeconds = null,
        totalIntents = intents.size,
    )

internal fun peerIntent(
    amount: Usdc6,
    currency: PeerCurrency? = null,
    fiatMinor: String? = null,
    status: PeerIntentStatus = PeerIntentStatus.SIGNALED,
    isExpired: Boolean = false,
): PeerIntent =
    PeerIntent(
        intentHash = "0x01",
        status = status,
        amount = amount,
        releasedAmount = Usdc6.ZERO,
        conversionRate = null,
        paymentCurrency = currency,
        paymentAmount = fiatMinor?.let { PeerFiat(BigInteger(it)) } ?: PeerFiat.ZERO,
        paymentId = null,
        signalTimestampSeconds = null,
        paymentTimestampSeconds = null,
        fulfillTimestampSeconds = null,
        pruneTimestampSeconds = null,
        expiryTimeSeconds = null,
        isExpired = isExpired,
        fillLatencySeconds = null,
        signalTxHash = null,
        fulfillTxHash = null,
        pruneTxHash = null,
    )

/**
 * The iOS encrypted store, as far as Kotlin can see it: two opaque JSON slots behind synchronous,
 * throwing calls, which is exactly the shape `ApplePeerCashOutStorage` is implemented in on device.
 */
internal class FakePeerStorage : ApplePeerCashOutStorage {
    private var checkpointBook: String? = null
    private var payeeBook: String? = null

    /** Set to make a read fail the way an unreachable or undecodable store would. */
    var failReads: Boolean = false

    override fun peerCheckpointBookJson(): AppleStorageValue = read { checkpointBook }

    override fun storePeerCheckpointBookJson(value: String) {
        checkpointBook = value
    }

    override fun peerPayeeBookJson(): AppleStorageValue = read { payeeBook }

    override fun storePeerPayeeBookJson(value: String) {
        payeeBook = value
    }

    private fun read(value: () -> String?): AppleStorageValue {
        check(!failReads) { "storage unavailable" }
        return AppleStorageValue(value())
    }
}

private const val ESCROW_HEX = "0x777777779d229cdf3110e9de47943791c26300ef"
