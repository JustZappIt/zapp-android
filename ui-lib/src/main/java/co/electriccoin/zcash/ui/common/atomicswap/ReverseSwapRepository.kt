// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.offramp.atomicswap.AtomicSwapActivity
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapDriver
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.SwapDirection
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.time.Duration.Companion.seconds

data class ReverseSwapState(
    val record: ReverseSwapRecord? = null,
    /** What holds the conversion up, while something does; it's tried again by itself. */
    val problem: AtomicSwapProblem? = null,
    val zcashWait: ZcashWait? = null,
)

/** The conversion from private USD to ZEC; only its funding and settlement wait for the user. */
interface ReverseSwapRepository : SwapConversionLifecycle {
    val state: StateFlow<ReverseSwapState>

    /** The conversions the user went ahead with, oldest first. */
    val history: Flow<List<ReverseSwapRecord>>

    /** A verified quote for [requested], kept as a preview until the user goes ahead with it. */
    suspend fun quote(requested: Usdc6): ReverseSwapRecord

    /** Goes ahead with the previewed quote: the maker accepts it, then its escrow is paid. Nothing commits before. */
    suspend fun fund(index: Int)

    /** The second authorization: lets the maker settle once its ZEC is in. */
    suspend fun ready(index: Int)

    suspend fun cancel(index: Int)

    /** Whether the refund Railgun returned to conversion [index], active or not, can be shielded again now. */
    suspend fun canRescue(index: Int): Boolean

    suspend fun rescue(index: Int)
}

internal class ReverseSwapRepositoryImpl(
    deployments: AtomicSwapDeployments,
    private val sessions: AtomicSwapSessions,
    private val store: ReverseSwapRecords,
    private val forward: AtomicSwapRecords,
    scheduler: AtomicSwapScheduler,
    notifier: AtomicSwapNotifier,
    private val spendGuard: PrivateUsdSpendGuard,
    scope: CoroutineScope = swapScope(),
    private val runner: SwapConversionRunner<ReverseSwapRecord> =
        SwapConversionRunner(
            conversions = ReverseSwapConversions(sessions, store, notifier),
            scheduler = scheduler,
            notifier = notifier,
            scope = scope,
            isAvailable = deployments.current != null,
        ),
) : ReverseSwapRepository,
    SwapConversionLifecycle by runner {
    private val deployment: AtomicSwapDeployment? = deployments.current

    override val state: StateFlow<ReverseSwapState> =
        combine(store.observeActive.unreadableAsNone(), runner.loop.progress) { record, progress ->
            val current = progress.takeIf { record != null && it.index == record.index } ?: SwapProgress()
            ReverseSwapState(record, current.problem, current.zcashWait)
        }.stateIn(scope, SharingStarted.Eagerly, ReverseSwapState())

    override val history: Flow<List<ReverseSwapRecord>> =
        store.observeHistory.catch { e ->
            Twig.error(e) { "Reverse swap: the store is unreadable" }
            emit(emptyList())
        }

    override suspend fun quote(requested: Usdc6): ReverseSwapRecord {
        if (forward.underWay() != null) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.SWAP_UNDER_WAY, "a conversion to private USD is under way")
        }
        return sessions.reverse(checkNotNull(deployment).swap).quote(requested)
    }

    override suspend fun fund(index: Int) =
        resumingAfter {
            val driver = driverFor(index)
            spendGuard.startConversion { sessions.acceptanceLock.withLock { driver.goAhead(index) } }
            driver.fund(index)
        }

    override suspend fun ready(index: Int) = resumingAfter { driverFor(index).ready(index) }

    override suspend fun cancel(index: Int) = resumingAfter { driverFor(index).cancel(index) }

    override suspend fun rescue(index: Int) = resumingAfter { driverFor(index).rescue(index) }

    override suspend fun canRescue(index: Int): Boolean = driverFor(index).canRescue(index)

    // Whatever the step came to, the loop picks the conversion up from there, unless the wallet was reset meanwhile.
    private suspend fun resumingAfter(step: suspend () -> Unit) {
        val session = sessions.session
        try {
            step()
        } finally {
            runner.resume(isForeground = true, session)
        }
    }

    // Each conversion on the deployment it was quoted on, whichever one is active now.
    private suspend fun driverFor(index: Int): ReverseSwapDriver =
        sessions.reverse(checkNotNull(store.find(index)) { "no conversion $index" }.deployment)
}

/** Reverse swaps as [SwapLoop] advances them. */
internal class ReverseSwapConversions(
    private val sessions: AtomicSwapSessions,
    private val store: ReverseSwapRecords,
    private val notifier: AtomicSwapNotifier,
) : SwapConversions<ReverseSwapRecord> {
    override val direction = SwapDirection.REVERSE

    override val session: Long get() = sessions.session

    override val active: Flow<ReverseSwapRecord?> = store.observeActive.unreadableAsNone()

    override suspend fun underWay(): ReverseSwapRecord? = store.underWay()

    override fun isUnderWay(record: ReverseSwapRecord) = record.underWay

    override fun indexOf(record: ReverseSwapRecord) = record.index

    override fun needsYou(record: ReverseSwapRecord) = record.phase == ReversePhase.AWAITING_READY

    override suspend fun advance(
        record: ReverseSwapRecord,
        session: Long,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): SwapLoopStep {
        val after = sessions.reverse(record.deployment, session).advance()
        return when {
            after == null -> {
                SwapLoopStep.Over
            }

            after.finished -> {
                notifier.finished(direction, R.string.reverse_title, after.phase.label())
                SwapLoopStep.Over
            }

            else -> {
                SwapLoopStep.Waiting(POLL_INTERVAL, needsYou = needsYou(after))
            }
        }
    }

    override fun settled() = Unit

    override suspend fun reset() = sessions.reset { store.clear() }

    private companion object {
        val POLL_INTERVAL = 15.seconds
    }
}

private fun Flow<ReverseSwapRecord?>.unreadableAsNone(): Flow<ReverseSwapRecord?> =
    catch { e ->
        Twig.error(e) { "Reverse swap: the store is unreadable" }
        emit(null)
    }
