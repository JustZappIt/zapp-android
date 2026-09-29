// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.exception.SdkException
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.offramp.atomicswap.AtomicSwapActivity
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.AtomicSwapOffer
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.AtomicSwapStep

data class AtomicSwapState(
    val record: AtomicSwapRecord? = null,
    val wait: AtomicSwapStep.Waiting? = null,
    val activity: AtomicSwapActivity? = null,
    val problem: AtomicSwapProblem? = null,
    val confirmations: Int? = null,
    /** Picked up after the app was closed, and no step has finished since. */
    val resuming: Boolean = false,
) {
    val isUnderWay: Boolean get() = record?.finished == false
}

enum class AtomicSwapProblem {
    RELAYER_UNREACHABLE,
    MAKER_UNREACHABLE,
    ETHEREUM_UNREACHABLE,
    RAILGUN_CLOSED,
    CLAIM_TURN,
    ZCASH_WALLET,
    UNEXPECTED,
}

/** An offer to show before accepting it; [depositFeeZat] is null when the wallet can't pay it now. */
data class AtomicSwapQuote(
    val offer: AtomicSwapOffer,
    val depositFeeZat: Long?,
)

/**
 * The one swap under way, run to its end a step at a time for as long as the process lives. The
 * worker keeps the process alive in the background.
 */
interface AtomicSwapRepository {
    val deployment: AtomicSwapDeployment?

    val state: StateFlow<AtomicSwapState>

    /** Every swap accepted on this device, oldest first. */
    val history: Flow<List<AtomicSwapRecord>>

    suspend fun quote(units: Int): AtomicSwapQuote

    /** Accepts [offer]; its deposit and the rest follow without the user. */
    suspend fun accept(offer: AtomicSwapOffer): AtomicSwapRecord

    /** Keeps a swap under way advancing. Only the foreground may start the worker's service. */
    fun resume(isForeground: Boolean)

    fun retryNow()

    /** Calls off a swap that never reached the chain. */
    suspend fun abandon()

    suspend fun isUnderWay(): Boolean

    suspend fun awaitSettled()

    /** Looks up, on the chain, the payout of paid swaps kept without one. */
    fun findMissingPayouts()
}

class AtomicSwapRepositoryImpl(
    deployments: AtomicSwapDeployments,
    private val reverseStore: ReverseSwapStoreImpl,
    private val sessions: AtomicSwapSessions,
    private val store: AtomicSwapStoreImpl,
    private val keys: AtomicSwapKeysImpl,
    private val zcash: AtomicSwapZcashInfo,
    private val scheduler: AtomicSwapScheduler,
    notifier: AtomicSwapNotifier,
) : AtomicSwapRepository {
    override val deployment: AtomicSwapDeployment? = deployments.current

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val driverLock = Mutex()
    private val loop = AtomicSwapLoop(sessions, driverLock, store, zcash, scheduler, notifier, scope)
    private var payoutLookup: Job? = null

    override val state: StateFlow<AtomicSwapState> =
        combine(
            store.observeActive.catch { e ->
                Twig.error(e) { "Atomic swap: the store is unreadable" }
                emit(null)
            },
            loop.progress,
        ) { record, progress ->
            val current = progress.takeIf { record != null && it.index == record.index } ?: AtomicSwapProgress()
            AtomicSwapState(
                record = record,
                wait = current.wait,
                activity = current.activity,
                problem = current.problem,
                confirmations = current.confirmations,
                resuming = current.resuming,
            )
        }.stateIn(scope, SharingStarted.Eagerly, AtomicSwapState())

    override val history: Flow<List<AtomicSwapRecord>> =
        store.observeHistory.catch { e ->
            Twig.error(e) { "Atomic swap: the store is unreadable" }
            emit(emptyList())
        }

    override suspend fun quote(units: Int): AtomicSwapQuote {
        requireForwardAvailable(reverseStore.active())
        val offer = driverLock.withLock { sessions.current.quote(units) }
        return AtomicSwapQuote(offer, depositFee(offer))
    }

    override suspend fun accept(offer: AtomicSwapOffer): AtomicSwapRecord {
        try {
            return store.acceptanceLock.withLock {
                requireForwardAvailable(reverseStore.active())
                driverLock.withLock { sessions.current.accept(offer) }
            }
        } finally {
            // An accept cut short may still have opened the swap: the loop finds out.
            if (store.active()?.let { it.index == offer.index && !it.finished } == true) {
                loop.start(resuming = false)
                scheduler.runNow()
            }
        }
    }

    override fun resume(isForeground: Boolean) {
        if (deployment == null) return
        scope.launch {
            if (!isUnderWay()) return@launch
            loop.start(resuming = true)
            if (isForeground) scheduler.runNow() else scheduler.runIfIdle()
        }
    }

    override fun retryNow() = loop.nudge()

    override suspend fun abandon() {
        val record = store.active() ?: return
        driverLock.withLock { sessions.forRecord(record).driver.abandon(record) }
        loop.settle()
    }

    override suspend fun isUnderWay(): Boolean = store.active()?.finished == false

    override suspend fun awaitSettled() {
        store.observeActive.first { it?.finished != false }
    }

    override fun findMissingPayouts() {
        if (deployment == null || payoutLookup?.isActive == true) return
        payoutLookup =
            scope.launch {
                history
                    .first()
                    .filter { it.outcome == AtomicSwapOutcome.Paid && it.payoutTx == null }
                    .forEach { findPayout(it) }
            }
    }

    private suspend fun findPayout(record: AtomicSwapRecord) {
        try {
            val tx =
                sessions.forRecord(record).chain.payoutTx(
                    record.swapId.hexToBytes(),
                    record.finishedAt ?: record.acceptedAt
                )
                    ?: return
            store.update(record.index) { it.copy(payoutTx = tx) }
        } catch (e: RpcException) {
            Twig.info { "Atomic swap: no payout found for ${record.index}, ${e.message}" }
        } catch (e: IllegalStateException) {
            Twig.info { "Atomic swap: no payout found for ${record.index}, ${e.message}" }
        } catch (e: StoreCorruptedException) {
            Twig.error(e) { "Atomic swap: the store is unreadable" }
        }
    }

    private suspend fun depositFee(offer: AtomicSwapOffer): Long? =
        try {
            zcash.depositFee(
                keys.depositAddress(offer.index, offer.quote.makerShare.hexToBytes()),
                offer.quote.depositZat,
            )
        } catch (e: SdkException) {
            Twig.info { "Atomic swap: no deposit fee estimate, ${e.message}" }
            null
        }
}

private fun requireForwardAvailable(record: xyz.justzappit.offramp.atomicswap.ReverseSwapRecord?) {
    if (record?.underWay == true) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.SWAP_UNDER_WAY, "a reverse swap is under way")
    }
}
