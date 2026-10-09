// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:UseSerializers(LowercaseAddressSerializer::class)

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.Usdc6

/** What a swap commits to at `open`, field for field the contract's `Terms`, of which it keeps only the [hash]. */
@Serializable
data class SwapTerms(
    val maker: Address,
    val token: Address,
    val amount: Usdc6,
    val makerKey: SwapShare,
    val userKey: SwapShare,
    val user: Address,
    val t0: Long,
    val t1: Long,
    /** Zero for a swap that pays [user]'s balance rather than a Railgun note. */
    val payoutNote: NoteCommitment,
) {
    /** `hashTerms`: keccak-256 of the eleven words `abi.encode` makes of them. */
    fun hash(): ByteArray {
        val head = listOf(AbiAddress(maker), AbiAddress(token), AbiUint(amount.micros))
        val tail =
            listOf(
                AbiAddress(user),
                AbiUint(bigIntegerValueOf(t0)),
                AbiUint(bigIntegerValueOf(t1)),
                AbiBytes32(payoutNote.bytes),
            )
        return keccak256(AbiEncoder.encode(head + makerKey.words() + userKey.words() + tail))
    }

    companion object {
        /** An escrow's terms as `openReverse` stores them: the escrowing user is their maker, the maker their user. */
        fun reverse(
            quote: ReverseQuote,
            userShare: SwapShare
        ) = SwapTerms(
            maker = quote.user,
            token = quote.terms.token,
            amount = quote.terms.amount,
            makerKey = userShare,
            userKey = quote.terms.makerShare,
            user = quote.terms.maker,
            t0 = quote.readyDeadline,
            t1 = quote.refundAfter,
            payoutNote = EMPTY_NOTE,
        )
    }
}

internal val EMPTY_NOTE = NoteCommitment.of(ByteArray(SWAP_WORD_BYTES))
