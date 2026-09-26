// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address

/** The Railgun note a payout is shielded to. The quote names its [commitment]; the relayer sends the rest. */
class PayoutNote(
    val npk: ByteArray,
    val encryptedBundle: List<ByteArray>,
    val shieldKey: ByteArray,
    val commitment: ByteArray,
)

class UserAcceptance(
    val userShare: ByteArray,
    val userProof: ByteArray,
    val viewingKeys: ByteArray,
)

/** The user's swap cryptography (libzecswap on Android), keyed by swap index. */
interface AtomicSwapKeys {
    /** `Z`, the public share a quote is accepted with. */
    suspend fun userShare(index: Int): ByteArray

    /** The swap's `user`: its own key, which signs for it and is never funded. */
    suspend fun authAddress(index: Int): Address

    suspend fun payoutNote(index: Int): PayoutNote

    /** Throws unless the maker's share proof verifies for this quote; proves ours in return. */
    suspend fun accept(
        index: Int,
        chainId: Long,
        contract: Address,
        quoteId: ByteArray,
        makerShare: ByteArray,
        makerProof: ByteArray,
    ): UserAcceptance

    /** The deposit address, from the maker share as the contract records it. */
    suspend fun depositAddress(
        index: Int,
        makerShare: ByteArray
    ): String

    /** `z`, which a claim reveals. */
    suspend fun claimSecret(index: Int): ByteArray

    suspend fun signLockClaim(
        index: Int,
        chainId: Long,
        contract: Address,
        swapId: ByteArray,
        deadline: Long,
    ): ByteArray

    suspend fun signPayout(
        index: Int,
        chainId: Long,
        contract: Address,
        swapId: ByteArray,
        relayer: Address,
        fee: BigInteger,
    ): ByteArray
}

/** The wallet's Zcash side (the SDK on Android). */
interface AtomicSwapZcash {
    suspend fun chainHeight(): Long

    /**
     * Pays [zatoshi] to [address] in one transaction and returns its id. Creating the transaction is
     * the point of no return, since the wallet may broadcast it on its own: callers record the attempt
     * before calling.
     */
    suspend fun pay(
        address: String,
        zatoshi: Long
    ): String

    /**
     * The id of a payment to [address] the wallet created, mined or still pending, or null if there
     * is none or it expired unmined: how a [pay] cut short is found again.
     */
    suspend fun findPayment(address: String): String?

    /**
     * Takes a refunded deposit home: watches the deposit account from [birthday], sweeps its balance
     * with the maker's revealed [makerSecret] added to the user's, and returns the sweep's id. A sweep
     * that already went out before an interruption is returned instead of a second one.
     */
    suspend fun sweepRefund(
        index: Int,
        makerShare: ByteArray,
        makerSecret: ByteArray,
        birthday: Long,
    ): String
}

/** Where swaps are kept between steps. */
interface AtomicSwapStore {
    /** The next swap index, counted as used before it is returned: an index is never reused. */
    suspend fun takeIndex(): Int

    suspend fun active(): AtomicSwapRecord?

    suspend fun save(record: AtomicSwapRecord)
}

/** Reads of the settlement chain. */
interface AtomicSwapChainReader {
    /** Null until the swap is open. */
    suspend fun swap(id: ByteArray): OnChainSwap?

    /** The latest block's time: the contract's clock. */
    suspend fun now(): Long

    /** Whether Railgun would take a shield of [token] now: not blocklisted, and not paused. */
    suspend fun railgunAccepts(token: Address): Boolean
}
