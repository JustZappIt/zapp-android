// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import xyz.justzappit.atomicswap.Deployment
import xyz.justzappit.atomicswap.ReverseAtomicSwap
import xyz.justzappit.atomicswap.ReverseEscrowTerms
import xyz.justzappit.offramp.atomicswap.ReverseRelayerTerms
import xyz.justzappit.offramp.atomicswap.ReverseSwapKeys
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.SWAP_ADDRESS_BYTES
import xyz.justzappit.offramp.atomicswap.SWAP_SHARE_BYTES
import xyz.justzappit.offramp.atomicswap.SWAP_WORD_BYTES
import xyz.justzappit.offramp.atomicswap.decimalUnits
import xyz.justzappit.offramp.atomicswap.fixedHex

class ReverseSwapKeysImpl(
    private val keys: AtomicSwapKeysImpl
) : ReverseSwapKeys {
    override suspend fun signOpen(record: ReverseSwapRecord): ByteArray =
        keys.withKey(record.index) { key ->
            val quote = record.quote
            ReverseAtomicSwap.signOpen(
                key,
                record.domain(),
                ReverseEscrowTerms(
                    fixedHex(quote.terms.maker, SWAP_ADDRESS_BYTES),
                    fixedHex(quote.terms.token, SWAP_ADDRESS_BYTES),
                    decimalUnits(quote.terms.amount),
                    fixedHex(quote.terms.makerShare, SWAP_SHARE_BYTES),
                    quote.readyDeadline,
                    quote.refundAfter,
                    quote.fundingDeadline,
                )
            )
        }

    override suspend fun signReady(record: ReverseSwapRecord, deadline: Long): ByteArray =
        keys.withKey(record.index) {
            ReverseAtomicSwap.signReady(it, record.domain(), fixedHex(record.swapId, SWAP_WORD_BYTES), deadline)
        }

    override suspend fun signLockRefund(record: ReverseSwapRecord, deadline: Long): ByteArray =
        keys.withKey(record.index) {
            ReverseAtomicSwap.signLockRefund(it, record.domain(), fixedHex(record.swapId, SWAP_WORD_BYTES), deadline)
        }

    override suspend fun signPayout(record: ReverseSwapRecord, terms: ReverseRelayerTerms): ByteArray =
        keys.withKey(record.index) {
            ReverseAtomicSwap.signRefundPayout(
                it,
                record.domain(),
                fixedHex(record.swapId, SWAP_WORD_BYTES),
                fixedHex(terms.relayer, SWAP_ADDRESS_BYTES),
                decimalUnits(terms.fee)
            )
        }

    override suspend fun signRescue(record: ReverseSwapRecord, terms: ReverseRelayerTerms): ByteArray =
        keys.withKey(record.index) {
            ReverseAtomicSwap.signRefundRescue(
                it,
                record.domain(),
                fixedHex(record.swapId, SWAP_WORD_BYTES),
                fixedHex(terms.relayer, SWAP_ADDRESS_BYTES),
                decimalUnits(terms.fee)
            )
        }

    private fun ReverseSwapRecord.domain() =
        Deployment(
            deployment.chainId,
            fixedHex(deployment.contract, SWAP_ADDRESS_BYTES)
        )
}
