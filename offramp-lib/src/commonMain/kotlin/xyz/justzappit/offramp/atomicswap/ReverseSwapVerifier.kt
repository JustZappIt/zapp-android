// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.types.Address

internal class ReverseSwapVerifier(
    private val deployment: ReverseDeployment,
    private val chain: ReverseSwapChain,
    private val keys: AtomicSwapKeys,
) {
    suspend fun state(record: ReverseSwapRecord): ReverseChainState {
        check(record.deployment == deployment) { "swap belongs to another deployment" }
        verifyQuote(
            record.quote,
            deployment,
            keys.authAddress(record.index).lowercaseHex,
            keys.payoutNote(record.index).commitment
        )
        check(
            record.swapId ==
                AtomicSwapChain
                    .swapId(
                        Address.parse(record.quote.user),
                        fixedHex(record.quote.terms.makerShare, SWAP_SHARE_BYTES)
                    ).hex()
        )
        check(fixedHex(record.userShare, SWAP_SHARE_BYTES).contentEquals(keys.userShare(record.index)))
        val state = chain.read(record.swapId)
        state.swap?.let {
            verifyEscrow(record, state)
            check(state.block - state.fundingBlock + 1 >= deployment.confirmations) { "escrow is not confirmed" }
        }
        return state
    }

    fun verifyInfo(info: ReverseMakerInfo) {
        check(info.apiVersion == 1 && info.reverseEnabled && info.zcashNetwork == "testnet")
        check(info.chainId == deployment.chainId && sameAddress(info.contract, deployment.contract))
        check(sameAddress(info.token, deployment.token) && sameAddress(info.maker, deployment.maker))
    }

    companion object {
        fun verifyQuote(quote: ReverseQuote, deployment: ReverseDeployment, user: String, note: ByteArray) {
            check(quote.terms.chainId == deployment.chainId && sameAddress(quote.terms.contract, deployment.contract))
            check(sameAddress(quote.terms.token, deployment.token) && sameAddress(quote.terms.maker, deployment.maker))
            check(sameAddress(quote.user, user) && fixedHex(quote.refundNote, SWAP_WORD_BYTES).contentEquals(note))
            check(note.any { it != 0.toByte() })
        }

        fun verifyEscrow(record: ReverseSwapRecord, state: ReverseChainState) {
            val swap = checkNotNull(state.swap)
            val quote = record.quote
            check(swap.maker == Address.parse(quote.user) && swap.user == Address.parse(quote.terms.maker))
            check(swap.makerShare.contentEquals(fixedHex(record.userShare, SWAP_SHARE_BYTES)))
            check(swap.userShare.contentEquals(fixedHex(quote.terms.makerShare, SWAP_SHARE_BYTES)))
            check(swap.token == Address.parse(quote.terms.token) && swap.amount == decimalUnits(quote.terms.amount))
            check(swap.t0 == quote.readyDeadline && swap.t1 == quote.refundAfter)
            check(
                swap.payoutNote.all { it == 0.toByte() } &&
                    state.refundNote.contentEquals(fixedHex(quote.refundNote, SWAP_WORD_BYTES))
            )
            check(state.fundingBlock in 1..state.block)
        }
    }
}
