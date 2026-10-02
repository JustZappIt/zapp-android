// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.exception.SdkException
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.justzappit.offramp.atomicswap.AtomicSwapActivity
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.AtomicSwapOffer
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.AtomicSwapStep
import xyz.justzappit.offramp.atomicswap.AtomicSwapWait
import xyz.justzappit.offramp.atomicswap.SwapDirection
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.time.Duration.Companion.seconds

data class AtomicSwapState(
    val record: AtomicSwapRecord? = null,
    val wait: AtomicSwapStep.Waiting? = null,
    val activity: AtomicSwapActivity? = null,
    val problem: AtomicSwapProblem? = null,
    val zcashWait: ZcashWait? = null,
    val confirmations: Int? = null,
    /** Picked up after the app was closed, and no step has finished since. */
    val resuming: Boolean = false,
) {
    val isUnderWay: Boolean get() = record?.finished == false
}

/** An offer to show before accepting it; [depositFeeZat] is null when the wallet can't pay it now. */
data class AtomicSwapQuote(
    val offer: AtomicSwapOffer,
    val depositFeeZat: Long?,
)

/** The one forward swap under way, run to its end a step at a time; the worker keeps the process alive meanwhile. */
interface AtomicSwapRepository : SwapConversionLifecycle {
    val deployment: AtomicSwapDeployment?

    val state: StateFlow<AtomicSwapState>

    /** Every swap accepted on this device, oldest first. */
    val history: Flow<List<AtomicSwapRecord>>

    suspend fun quote(requested: Usdc6): AtomicSwapQuote

    /** Accepts [offer]; its deposit and the rest follow without the user. */
    suspend fun accept(offer: AtomicSwapOffer): AtomicSwapRecord

    /** Calls off a swap that never reached the chain. */
    suspend fun abandon()

    suspend fun payoutFee(index: Int): Usdc6

    suspend fun approvePayoutFee(record: AtomicSwapRecord, fee: Usdc6)

    /** Looks up, on the chain, the payout of paid swaps kept without one. */
    fun findMissingPayouts()
}

internal class AtomicSwapRepositoryImpl(
    deployments: AtomicSwapDeployments,
    private val reverse: ReverseSwapRepository,
    private val sessions: AtomicSwapSessions,
    private val store: AtomicSwapRecords,
    private val zcash: AtomicSwapZcashInfo,
    private val scheduler: AtomicSwapScheduler,
    notifier: AtomicSwapNotifier,
    private val spendGuard: PrivateUsdSpendGuard,
    scope: CoroutineScope = swapScope(),
    private val driverLock: Mutex = Mutex(),
    private val payouts: AtomicSwapPayouts = AtomicSwapPayouts(sessions, store, scope),
    private val runner: SwapConversionRunner<AtomicSwapRecord> =
        SwapConversionRunner(
            conversions = AtomicSwapConversions(sessions, driverLock, store, zcash, scheduler, notifier, payouts),
            scheduler = scheduler,
            notifier = notifier,
            scope = scope,
            isAvailable = deployments.current != null,
        ),
) : AtomicSwapRepository,
    SwapConversionLifecycle by runner {
    override val deployment: AtomicSwapDeployment? = deployments.current

    override val state: StateFlow<AtomicSwapState> =
        combine(store.observeActive.unreadableAsNone(), runner.loop.progress) { record, progress ->
            val current = progress.takeIf { record != null && it.index == record.index } ?: SwapProgress()
            AtomicSwapState(
                record = record,
                wait = current.wait,
                activity = current.activity,
                problem = current.problem,
                zcashWait = current.zcashWait,
                confirmations = current.confirmations,
                resuming = current.resuming,
            )
        }.stateIn(scope, SharingStarted.Eagerly, AtomicSwapState())

    override val history: Flow<List<AtomicSwapRecord>> =
        store.observeHistory.catch { e ->
            Twig.error(e) { "Atomic swap: the store is unreadable" }
            emit(emptyList())
        }

    override suspend fun quote(requested: Usdc6): AtomicSwapQuote {
        reverse.requireNotUnderWay()
        val offer = driverLock.withLock { sessions.forward(checkNotNull(deployment)).quote(requested) }
        return AtomicSwapQuote(offer, zcash.depositFee(offer))
    }

    override suspend fun accept(offer: AtomicSwapOffer): AtomicSwapRecord {
        val session = sessions.session
        try {
            return spendGuard.startConversion {
                sessions.acceptanceLock.withLock {
                    reverse.requireNotUnderWay()
                    driverLock.withLock { sessions.forward(checkNotNull(deployment), session).accept(offer) }
                }
            }
        } finally {
            // An accept cut short may still have opened the swap: the loop finds out, unless the wallet was reset.
            withContext(NonCancellable) {
                if (store.underWay()?.index == offer.index && runner.loop.start(resuming = false, session)) {
                    scheduler.runNow()
                }
            }
        }
    }

    // What the loop last kept decides, not what was read before the lock: it may have paid the deposit since.
    override suspend fun abandon() {
        val abandoned = driverLock.withLock { store.underWay()?.let { sessions.forward(it).abandon(it) } }
        if (abandoned != null) runner.loop.settle()
    }

    override suspend fun payoutFee(index: Int): Usdc6 =
        driverLock.withLock {
            val record = checkNotNull(store.underWay()?.takeIf { it.index == index })
            sessions.forward(record).payoutFees.quote(index)
        }

    override suspend fun approvePayoutFee(record: AtomicSwapRecord, fee: Usdc6) {
        driverLock.withLock {
            check(store.underWay() == record) { "the conversion changed; review its fee again" }
            sessions.forward(record).payoutFees.approve(record, fee)
        }
        retryNow()
    }

    override fun findMissingPayouts() {
        if (deployment != null) payouts.findMissing { history.first() }
    }
}

/** Forward swaps as [SwapLoop] advances them; the wakes around their deadlines are set from here. */
internal class AtomicSwapConversions(
    private val sessions: AtomicSwapSessions,
    private val driverLock: Mutex,
    private val store: AtomicSwapRecords,
    private val zcash: AtomicSwapZcashInfo,
    private val scheduler: AtomicSwapScheduler,
    private val notifier: AtomicSwapNotifier,
    private val payouts: AtomicSwapPayouts,
) : SwapConversions<AtomicSwapRecord> {
    private var wakesFor: Pair<Long, Long>? = null

    override val direction = SwapDirection.FORWARD

    override val session: Long get() = sessions.session

    override val active: Flow<AtomicSwapRecord?> = store.observeActive.unreadableAsNone()

    override suspend fun underWay(): AtomicSwapRecord? = store.underWay()

    override fun isUnderWay(record: AtomicSwapRecord) = !record.finished

    override fun indexOf(record: AtomicSwapRecord) = record.index

    override fun needsYou(record: AtomicSwapRecord) = false

    override suspend fun advance(
        record: AtomicSwapRecord,
        session: Long,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): SwapLoopStep {
        val step =
            driverLock.withLock {
                val current = store.active()?.takeIf { it.index == record.index } ?: record
                sessions.forward(current, session).advance(current, onActivity)
            }
        return when (step) {
            is AtomicSwapStep.Finished -> {
                announce(step.outcome)
                SwapLoopStep.Over
            }

            is AtomicSwapStep.Waiting -> {
                wake(step)
                SwapLoopStep.Waiting(pollInterval(step.reason), step, confirmations(step))
            }
        }
    }

    override fun settled() {
        wakesFor = null
        scheduler.cancelWakes()
    }

    override suspend fun reset() {
        payouts.cancel()
        sessions.reset { store.clear() }
    }

    private fun announce(outcome: AtomicSwapOutcome) =
        when (outcome) {
            AtomicSwapOutcome.Paid -> {
                notifier.finished(
                    direction,
                    R.string.private_usd_notification_paid_title,
                    R.string.private_usd_notification_paid_body
                )
            }

            is AtomicSwapOutcome.Refunded -> {
                notifier.finished(
                    direction,
                    R.string.convert_result_refunded_title,
                    R.string.private_usd_notification_refunded_body
                )
            }

            is AtomicSwapOutcome.NothingSent -> {
                notifier.finished(
                    direction,
                    R.string.private_usd_notification_nothing_title,
                    R.string.private_usd_notification_nothing_body
                )
            }
        }

    private fun wake(step: AtomicSwapStep.Waiting) {
        val t0 = step.t0
        val t1 = step.t1
        if (t0 != null && t1 != null && wakesFor != t0 to t1) {
            scheduler.wakeAt(t0, t1)
            wakesFor = t0 to t1
        }
    }

    private suspend fun confirmations(step: AtomicSwapStep.Waiting): Int? {
        val txId = store.underWay()?.deposit?.txId
        return if (step.reason == AtomicSwapWait.CONFIRMING && txId != null) {
            try {
                zcash.confirmations(txId)
            } catch (e: SdkException) {
                Twig.info { "Atomic swap: no confirmation count, ${e.message}" }
                null
            }
        } else {
            null
        }
    }

    private companion object {
        fun pollInterval(reason: AtomicSwapWait) =
            when (reason) {
                AtomicSwapWait.OPENING -> 5.seconds
                AtomicSwapWait.CONFIRMING -> 20.seconds
                AtomicSwapWait.DEPOSIT_UNSETTLED, AtomicSwapWait.REFUNDING -> 30.seconds
            }
    }
}

private suspend fun ReverseSwapRepository.requireNotUnderWay() {
    if (isUnderWay()) throw AtomicSwapBlockedException(AtomicSwapBlock.SWAP_UNDER_WAY, "a reverse swap is under way")
}

private fun Flow<AtomicSwapRecord?>.unreadableAsNone(): Flow<AtomicSwapRecord?> =
    catch { e ->
        Twig.error(e) { "Atomic swap: the store is unreadable" }
        emit(null)
    }
