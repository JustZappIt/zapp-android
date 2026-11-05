// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRecords
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRecords
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositStore
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendLog
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendGuard
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.deposits.DepositRecord
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Thrown before anything is deleted, so an unhandled one leaves the wallet intact. [count] is how many conversions,
 * deposits and sends are under way, or null when one of their stores or refunds could not be read. With none under
 * way, [hasRefund] says a finished conversion still has a refund to recover.
 */
class UnfinishedConversionsException(
    val count: Int?,
    val hasRefund: Boolean = false,
) : IllegalStateException(
        when {
            count == null -> "Private USD's records could not be read; refusing to destroy them"
            hasRefund -> "A refund still to recover would be lost"
            else -> "$count unfinished Private USD conversions would be lost"
        }
    )

/**
 * Refuses a destructive action while a Shielded USD conversion, deposit or send is still under way, or a refund from
 * one that ended hasn't been recovered.
 *
 * Each is carried to its end, or to its refund, by records that live in the encrypted preferences every wipe
 * clears: a swap's keys and notes, a deposit's signed authorization and note, a send's proof. Without them the funds
 * in it are lost, and the recovery phrase brings none of it back. Shared by every path that clears the preferences,
 * next to [EnsureNoUnsharedGiftFundsUseCase], and read the same way: a store that can't be read blocks, because
 * guessing "empty" wrong destroys money.
 */
class EnsureNoUnfinishedConversionsUseCase(
    private val forward: AtomicSwapRecords,
    private val reverse: ReverseSwapRecords,
    private val refunds: ReverseSwapRepository,
    private val deposits: PrivateUsdDepositStore,
    private val sends: PrivateUsdSendLog,
    private val spendGuard: PrivateUsdSpendGuard,
    private val clock: Clock = Clock.System,
) {
    /** @throws UnfinishedConversionsException before the caller has touched anything. */
    suspend operator fun invoke() {
        val count = unfinishedCount()
        if (count != 0) throw UnfinishedConversionsException(count)
        refundInTheWay()?.let { throw it }
    }

    /** Railgun's refund to a finished conversion, if one is still in its vault or that can't be told. */
    private suspend fun refundInTheWay(): UnfinishedConversionsException? {
        val hasRefund =
            read("The refunds") {
                val refunded = reverse.observeHistory.first().filter { it.phase == ReversePhase.REFUNDED }
                withTimeoutOrNull(REFUND_CHECK_TIMEOUT) { refunded.asReversed().any { refunds.canRescue(it.index) } }
                    ?: error("the refund check timed out")
            }
        return when (hasRefund) {
            true -> UnfinishedConversionsException(count = 0, hasRefund = true)
            false -> null
            null -> UnfinishedConversionsException(count = null)
        }
    }

    /** What is under way across the stores; null when one can't be read, which blocks as a count would. */
    private suspend fun unfinishedCount(): Int? {
        val now = clock.now().epochSeconds
        val counts =
            listOf(
                read("The conversion store") { if (forward.active()?.finished == false) 1 else 0 },
                read("The conversion-back store") { if (reverse.active()?.underWay == true) 1 else 0 },
                read("The deposit store") { deposits.records().count { it.isUnfinished(now) } },
                read("The send log") { sends.unsettledCount() },
            )
        // A conversion or send being committed right now has no record in those stores yet.
        val committing =
            when (spendGuard.state.value) {
                PrivateUsdSpendStatus.SENDING, PrivateUsdSpendStatus.CONVERTING -> 1
                else -> 0
            }
        return counts
            .filterNotNull()
            .takeIf { it.size == counts.size }
            ?.sum()
            ?.coerceAtLeast(committing)
    }

    private companion object {
        val REFUND_CHECK_TIMEOUT = 30.seconds
    }
}

/** Signed or posted, with nothing landed, and still able to land: the money is at its address or on its way. */
private fun DepositRecord.isUnfinished(now: Long): Boolean = !state.isFinished && landing == null && expiresAt > now

/** [read]'s answer, or null for a store that can't be read, logged: the caller refuses either way. */
private suspend fun <T : Any> read(
    what: String,
    read: suspend () -> T,
): T? =
    runCatching { read() }.getOrElse { throwable ->
        if (throwable is CancellationException) throw throwable
        Twig.error(throwable) { "$what could not be read; refusing to destroy it" }
        null
    }
