// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapProblem
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord

/** The conversion under way, into private USD or out of it; only one runs at a time. */
sealed interface PrivateUsdConversion {
    data class ToUsd(
        val swap: AtomicSwapState,
        /** Zcash confirmations the maker waits for. */
        val confirmationsNeeded: Int?,
    ) : PrivateUsdConversion

    data class ToZec(
        val record: ReverseSwapRecord,
        /** What holds it up, while something does; it's tried again by itself. */
        val problem: AtomicSwapProblem?,
    ) : PrivateUsdConversion
}

class ObservePrivateUsdConversionUseCase(
    private val atomicSwapRepository: AtomicSwapRepository,
    private val reverseSwapRepository: ReverseSwapRepository,
) {
    operator fun invoke(): Flow<PrivateUsdConversion?> =
        combine(atomicSwapRepository.state, reverseSwapRepository.state) { forward, reverse ->
            val record = reverse.record
            when {
                forward.isUnderWay -> {
                    PrivateUsdConversion.ToUsd(forward, atomicSwapRepository.deployment?.makerConfirmations)
                }

                record?.underWay == true -> {
                    PrivateUsdConversion.ToZec(record, reverse.problem)
                }

                else -> {
                    null
                }
            }
        }.distinctUntilChanged()
}
