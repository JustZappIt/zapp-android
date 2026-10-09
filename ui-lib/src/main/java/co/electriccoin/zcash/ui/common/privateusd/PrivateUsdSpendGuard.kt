// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRecords
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRecords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration.Companion.seconds

enum class PrivateUsdSpendStatus {
    CHECKING,
    AVAILABLE,
    SENDING,
    CONVERTING,
    UNREADABLE;

    val canSend: Boolean get() = this == AVAILABLE

    val canStartConversion: Boolean get() = this == AVAILABLE || this == CONVERTING
}

class PrivateUsdSpendBlockedException : IllegalStateException("an earlier private USD payment is unresolved")

/** One outgoing payment per wallet, including across screens and process restarts. */
class PrivateUsdSpendGuard(
    private val sends: PrivateUsdSendLog,
    private val forward: AtomicSwapRecords,
    private val reverse: ReverseSwapRecords,
    scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val starting = MutableStateFlow<PrivateUsdSpendStatus?>(null)

    val state =
        combine(
            starting,
            sends.observeUnsettled,
            forward.observeActive,
            reverse.observeActive,
        ) { starting, pending, usd, zec ->
            when {
                starting != null -> starting
                pending -> PrivateUsdSpendStatus.SENDING
                usd?.finished == false || zec?.underWay == true -> PrivateUsdSpendStatus.CONVERTING
                else -> PrivateUsdSpendStatus.AVAILABLE
            }
        }.retryWhen { _, _ ->
            emit(PrivateUsdSpendStatus.UNREADABLE)
            delay(RETRY_AFTER)
            true
        }.stateIn(scope, SharingStarted.Eagerly, PrivateUsdSpendStatus.CHECKING)

    suspend fun send(block: suspend () -> PrivateUsdSendOutcome): PrivateUsdSendOutcome {
        if (!lock.tryLock()) return PrivateUsdSendOutcome.Busy
        try {
            val converting = forward.active()?.finished == false || reverse.active()?.underWay == true
            return if (sends.hasUnsettled() || converting) {
                PrivateUsdSendOutcome.Busy
            } else {
                starting.value = PrivateUsdSpendStatus.SENDING
                block()
            }
        } finally {
            starting.value = null
            lock.unlock()
        }
    }

    /** Commits a conversion before another sender can reserve the same wallet's notes or nonce. */
    suspend fun <T> startConversion(block: suspend () -> T): T {
        if (!lock.tryLock()) throw PrivateUsdSpendBlockedException()
        try {
            if (sends.hasUnsettled()) throw PrivateUsdSpendBlockedException()
            // Drivers can advance an existing conversion only while both recovery stores can be read.
            forward.active()
            reverse.active()
            starting.value = PrivateUsdSpendStatus.CONVERTING
            return block()
        } finally {
            starting.value = null
            lock.unlock()
        }
    }

    private companion object {
        val RETRY_AFTER = 30.seconds
    }
}
