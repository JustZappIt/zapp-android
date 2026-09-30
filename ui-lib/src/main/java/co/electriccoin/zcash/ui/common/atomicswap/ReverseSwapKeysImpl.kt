// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import xyz.justzappit.atomicswap.ReverseAtomicSwap
import xyz.justzappit.atomicswap.ReverseEscrowTerms
import xyz.justzappit.offramp.atomicswap.RelayerTerms
import xyz.justzappit.offramp.atomicswap.ReverseSwapKeys
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord

class ReverseSwapKeysImpl(
    private val keys: SwapKeyring
) : ReverseSwapKeys {
    override suspend fun signOpen(record: ReverseSwapRecord): ByteArray =
        keys.withKey(record.index, record.railgunKeys) { key, railgun ->
            val quote = record.quote
            ReverseAtomicSwap.signOpen(
                key,
                railgun,
                record.domain(),
                ReverseEscrowTerms(
                    quote.terms.maker.bytes,
                    quote.terms.token.bytes,
                    quote.terms.amount.micros,
                    quote.terms.makerShare.bytes,
                    quote.readyDeadline,
                    quote.refundAfter,
                    quote.fundingDeadline,
                )
            )
        }

    override suspend fun signReady(
        record: ReverseSwapRecord,
        deadline: Long
    ): ByteArray =
        keys.withKey(record.index) {
            ReverseAtomicSwap.signReady(it, record.domain(), record.swapId.bytes, deadline)
        }

    override suspend fun signLockRefund(
        record: ReverseSwapRecord,
        deadline: Long
    ): ByteArray =
        keys.withKey(record.index) {
            ReverseAtomicSwap.signLockRefund(it, record.domain(), record.swapId.bytes, deadline)
        }

    override suspend fun signPayout(
        record: ReverseSwapRecord,
        terms: RelayerTerms
    ): ByteArray =
        keys.withKey(record.index) {
            ReverseAtomicSwap.signRefundPayout(
                it,
                record.domain(),
                record.swapId.bytes,
                terms.relayer.bytes,
                terms.fee.micros,
            )
        }

    override suspend fun signRescue(
        record: ReverseSwapRecord,
        terms: RelayerTerms
    ): ByteArray =
        keys.withKey(record.index, record.railgunKeys) { key, railgun ->
            ReverseAtomicSwap.signRefundRescue(
                key,
                railgun,
                record.domain(),
                record.swapId.bytes,
                terms.relayer.bytes,
                terms.fee.micros,
            )
        }

    private fun ReverseSwapRecord.domain() = domain(deployment.chainId, deployment.contract)
}
