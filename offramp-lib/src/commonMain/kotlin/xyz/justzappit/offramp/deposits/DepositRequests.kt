// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:UseSerializers(LowercaseAddressSerializer::class)

package xyz.justzappit.offramp.deposits

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import xyz.justzappit.evm.deposits.DepositNote
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.atomicswap.LowercaseAddressSerializer
import xyz.justzappit.offramp.atomicswap.SWAP_WORD_BYTES
import xyz.justzappit.offramp.atomicswap.SwapNote
import xyz.justzappit.offramp.atomicswap.fixedHex
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * `POST /v1/deposits/shield`'s body: a deposit address's EIP-3009 authorization of its whole balance to the Deposits
 * contract, whose nonce commits to [note], [fee] and the relayer's deposits account. Exactly these fields, since the
 * relayer answers any other with `422`. It carries the address's signature and the wallet's note: never show or log
 * one, so [toString] tells nothing of it.
 */
@Serializable
data class ShieldDeposit(
    val chainId: ChainId,
    val contract: Address,
    /** The deposit address. */
    val from: Address,
    /** Token base units the authorization moves: the address's whole balance when signed. */
    val value: Usdc6,
    val validAfter: Long,
    val validBefore: Long,
    val note: SwapNote,
    /** What the relayer keeps. */
    val fee: Usdc6,
    /** `r ‖ s ‖ v` over the token's EIP-712 digest of the authorization. */
    val signature: String,
) {
    override fun toString(): String = "ShieldDeposit"
}

/**
 * `POST /v1/deposits/rescue`'s body: shields what Railgun's screening sent back to [from]'s vault again, to [note],
 * less [fee], on [from]'s signature, with [nonce] the contract's `rescueNonces(from)`. As a shield, never shown.
 */
@Serializable
data class RescueDeposit(
    val chainId: ChainId,
    val contract: Address,
    val from: Address,
    val note: SwapNote,
    val fee: Usdc6,
    val nonce: Long,
    val deadline: Long,
    val signature: String,
) {
    override fun toString(): String = "RescueDeposit"
}

/** [this] as a request's note: its five words, `0x` and lowercase. */
fun DepositNote.wire(): SwapNote = SwapNote(npk.word(), encryptedBundle.map { it.word() }, shieldKey.word())

/** A request's [SwapNote] as the words the contract and the nonce take; throws on one that isn't five 32-byte words. */
fun SwapNote.depositNote(): DepositNote =
    DepositNote(
        fixedHex(npk, SWAP_WORD_BYTES),
        encryptedBundle.map { fixedHex(it, SWAP_WORD_BYTES) },
        fixedHex(shieldKey, SWAP_WORD_BYTES),
    )

/** Throws unless [request] fits the relayer's 16 KiB as [serializer] writes it, as it's posted. */
internal fun <T> requireFits(
    request: T,
    serializer: KSerializer<T>,
) {
    val size = requestJson.encodeToString(serializer, request).encodeToByteArray().size
    check(size <= MAX_REQUEST_BYTES) { "the request is larger than the relayer takes" }
}

private fun ByteArray.word(): String = "0x" + toHex()

private val requestJson =
    Json {
        explicitNulls = false
    }

/** The most a deposit request may be: the relayer's limit. */
private const val MAX_REQUEST_BYTES = 16 * 1024
