// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiBytes
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiTuple
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address

object ReverseFundingCalls {
    private const val OPEN_REVERSE =
        "openReverse((address,address,address,uint128,uint256[2],uint256[2],uint64,uint64,bytes32,uint64),bytes)"

    fun encode(record: ReverseSwapRecord, signature: ByteArray): List<Pair<String, ByteArray>> {
        require(signature.size == SWAP_SIGNATURE_BYTES)
        val quote = record.quote
        val amount = decimalUnits(quote.terms.amount)
        val approval =
            AbiEncoder.encodeFunctionCall(
                "approve(address,uint256)",
                listOf(AbiAddress(Address.parse(quote.terms.contract)), AbiUint(amount))
            )
        val makerShare = fixedHex(quote.terms.makerShare, SWAP_SHARE_BYTES)
        val userShare = fixedHex(record.userShare, SWAP_SHARE_BYTES)
        val open =
            AbiEncoder.encodeFunctionCall(
                OPEN_REVERSE,
                listOf(
                    AbiTuple(
                        listOf(
                            AbiAddress(Address.parse(quote.terms.maker)),
                            AbiAddress(Address.parse(quote.user)),
                            AbiAddress(Address.parse(quote.terms.token)),
                            AbiUint(amount),
                            AbiBytes32(makerShare.copyOfRange(0, SWAP_WORD_BYTES)),
                            AbiBytes32(makerShare.copyOfRange(SWAP_WORD_BYTES, SWAP_SHARE_BYTES)),
                            AbiBytes32(userShare.copyOfRange(0, SWAP_WORD_BYTES)),
                            AbiBytes32(userShare.copyOfRange(SWAP_WORD_BYTES, SWAP_SHARE_BYTES)),
                            AbiUint(bigIntegerValueOf(quote.readyDeadline)),
                            AbiUint(bigIntegerValueOf(quote.refundAfter)),
                            AbiBytes32(fixedHex(quote.refundNote, SWAP_WORD_BYTES)),
                            AbiUint(bigIntegerValueOf(quote.fundingDeadline)),
                        )
                    ),
                    AbiBytes(signature)
                ),
            )
        return listOf(quote.terms.token to approval, quote.terms.contract to open)
    }
}
