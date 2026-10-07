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
import xyz.justzappit.offramp.p2p.Usdc6

/** The calls a reverse swap's funding makes from Railgun: approve exactly the escrow, open it, then pay the relayer. */
object ReverseFundingCalls {
    private const val OPEN_REVERSE =
        "openReverse((address,address,address,uint128,uint256[2],uint256[2],uint64,uint64,bytes32,uint64),bytes)"

    fun encode(
        record: ReverseSwapRecord,
        signature: ByteArray,
        fee: Usdc6,
    ): List<Pair<Address, ByteArray>> {
        require(signature.size == SWAP_SIGNATURE_BYTES) { "a signature is $SWAP_SIGNATURE_BYTES bytes" }
        val quote = record.quote
        val amount = AbiUint(quote.terms.amount.micros)
        val approval =
            AbiEncoder.encodeFunctionCall("approve(address,uint256)", listOf(AbiAddress(quote.terms.contract), amount))
        val parties = listOf(quote.terms.maker, quote.user, quote.terms.token).map(::AbiAddress) + amount
        val shares = quote.terms.makerShare.words() + record.userShare.words()
        val deadlines =
            listOf(
                AbiUint(bigIntegerValueOf(quote.readyDeadline)),
                AbiUint(bigIntegerValueOf(quote.refundAfter)),
                AbiBytes32(quote.refundNote.bytes),
                AbiUint(bigIntegerValueOf(quote.fundingDeadline)),
            )
        val terms = AbiTuple(parties + shares + deadlines)
        val open = AbiEncoder.encodeFunctionCall(OPEN_REVERSE, listOf(terms, AbiBytes(signature)))
        val payment =
            AbiEncoder.encodeFunctionCall(
                "transfer(address,uint256)",
                listOf(AbiAddress(record.deployment.relayer), AbiUint(fee.micros)),
            )
        return listOf(quote.terms.token to approval, quote.terms.contract to open, quote.terms.token to payment)
    }
}
