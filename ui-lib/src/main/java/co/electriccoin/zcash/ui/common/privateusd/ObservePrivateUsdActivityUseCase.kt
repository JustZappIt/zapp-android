// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import kotlin.time.Instant

/** One conversion or send made on this device. */
sealed interface PrivateUsdActivityData {
    val timestamp: Instant

    data class ToUsd(
        val record: AtomicSwapRecord
    ) : PrivateUsdActivityData {
        override val timestamp = Instant.fromEpochSeconds(record.end?.at ?: record.acceptedAt)
    }

    data class ToZec(
        val record: ReverseSwapRecord
    ) : PrivateUsdActivityData {
        // Records from before the time was kept fall back to their quote, which ran out minutes after it.
        override val timestamp = Instant.fromEpochSeconds(record.acceptedAt ?: record.quote.terms.expiresAt)
    }

    data class Sent(
        val record: PrivateUsdSendRecord
    ) : PrivateUsdActivityData {
        override val timestamp = Instant.fromEpochSeconds(record.sentAt)
    }

    data class Proving(
        val send: PrivateUsdPendingSend
    ) : PrivateUsdActivityData {
        override val timestamp = Instant.fromEpochSeconds(send.startedAt)
    }
}

/** Every conversion, send and withdrawal made on this device, newest first. */
class ObservePrivateUsdActivityUseCase(
    private val atomicSwapRepository: AtomicSwapRepository,
    private val reverseSwapRepository: ReverseSwapRepository,
    private val sendLog: PrivateUsdSendLog,
) {
    operator fun invoke(): Flow<List<PrivateUsdActivityData>> =
        combine(atomicSwapRepository.history, reverseSwapRepository.history, sendLog.observe) { toUsd, toZec, sends ->
            (
                toUsd.map(PrivateUsdActivityData::ToUsd) +
                    toZec.map(PrivateUsdActivityData::ToZec) +
                    sends.sends.map(PrivateUsdActivityData::Sent) +
                    sends.pending.map(PrivateUsdActivityData::Proving)
            ).sortedByDescending { it.timestamp }
        }
}
