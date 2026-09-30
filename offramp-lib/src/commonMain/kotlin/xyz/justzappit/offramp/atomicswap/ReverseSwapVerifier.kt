// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.types.Address

/** Checks everything a reverse swap acts on against its deployment, its keys, and the confirmed chain. */
internal class ReverseSwapVerifier(
    private val deployment: SwapDeployment,
    private val chain: ReverseSwapChain,
    private val keys: AtomicSwapKeys,
    private val relayer: SwapRelayer,
) {
    /** The chain as far as [record] is concerned, once the record itself checks out. */
    suspend fun state(record: ReverseSwapRecord): ReverseChainState {
        check(record.deployment == deployment) { "the conversion belongs to another deployment" }
        verifyQuote(
            record.quote,
            deployment,
            keys.authAddress(record.index),
            keys.payoutNote(record.index, record.railgunKeys).commitment,
        )
        requireMatch(
            record.swapId == SwapId.of(record.quote.user, record.quote.terms.makerShare) &&
                record.userShare == keys.userShare(record.index),
        ) { "the conversion's ids aren't its keys'" }
        val state = chain.read(record.swapId)
        if (state.swap != null) {
            verifyEscrow(record, state)
            if (state.block - state.fundingBlock + 1 < deployment.escrowConfirmations) {
                throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_LAGGING, "the escrow isn't confirmed")
            }
        }
        return state
    }

    /** The relayer's terms, when they serve [record]'s deployment for no more than the swap allows. */
    suspend fun relayerTerms(record: ReverseSwapRecord): RelayerTerms =
        relayer.terms().also { it.checkedFee(record.deployment, record.quote.terms.amount) }

    companion object {
        fun verifyQuote(
            quote: ReverseQuote,
            deployment: SwapDeployment,
            user: Address,
            note: NoteCommitment,
        ) {
            quote.requireWellFormed()
            val terms = quote.terms
            val serves =
                terms.chainId == deployment.chainId &&
                    terms.contract == deployment.contract &&
                    terms.token == deployment.token &&
                    terms.maker == deployment.maker
            if (!serves) {
                throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "a quote for another deployment")
            }
            requireMatch(quote.user == user && quote.refundNote == note && note != EMPTY_NOTE) {
                "the quote refunds someone else"
            }
        }

        // Its roles are the forward swap's turned round: our key opens it, and the maker's is its user.
        fun verifyEscrow(
            record: ReverseSwapRecord,
            state: ReverseChainState
        ) {
            val swap = checkNotNull(state.swap)
            val quote = record.quote
            requireMatch(
                swap.maker == quote.user &&
                    swap.user == quote.terms.maker &&
                    swap.makerShare == record.userShare &&
                    swap.userShare == quote.terms.makerShare &&
                    swap.token == quote.terms.token &&
                    swap.amount == quote.terms.amount &&
                    swap.t0 == quote.readyDeadline &&
                    swap.t1 == quote.refundAfter &&
                    swap.payoutNote == EMPTY_NOTE &&
                    state.refundNote == quote.refundNote &&
                    state.fundingBlock in 1..state.block,
            ) { "the escrow isn't the one this conversion opened" }
        }

        private val EMPTY_NOTE = NoteCommitment.of(ByteArray(SWAP_WORD_BYTES))

        private fun requireMatch(
            matches: Boolean,
            what: () -> String
        ) {
            if (!matches) throw AtomicSwapBlockedException(AtomicSwapBlock.MISMATCH, what())
        }
    }
}
