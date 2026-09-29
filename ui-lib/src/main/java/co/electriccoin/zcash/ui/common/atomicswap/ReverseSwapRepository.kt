// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.ZcashNetworkProvider
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.offramp.atomicswap.ReverseDeployment
import xyz.justzappit.offramp.atomicswap.ReverseSwapChainImpl
import xyz.justzappit.offramp.atomicswap.ReverseSwapClient
import xyz.justzappit.offramp.atomicswap.ReverseSwapDriver
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import kotlin.time.Duration.Companion.seconds

internal data class ReverseSwapState(
    val record: ReverseSwapRecord? = null,
    val failed: Boolean = false
)

@Suppress("TooManyFunctions")
class ReverseSwapRepository(
    private val store: ReverseSwapStoreImpl,
    private val indices: AtomicSwapStoreImpl,
    private val keys: AtomicSwapKeysImpl,
    private val reverseKeys: ReverseSwapKeysImpl,
    private val zcash: ReverseSwapZcashImpl,
    private val wallet: RailgunWalletRepository,
    private val http: HttpClient,
    private val scheduler: AtomicSwapScheduler,
    private val notifier: AtomicSwapNotifier,
    network: ZcashNetworkProvider,
) {
    val available = network() == ZcashNetwork.Testnet
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val failed = MutableStateFlow(false)
    private var job: Job? = null
    private val drivers = mutableMapOf<ReverseDeployment, ReverseSwapDriver>()
    internal val state =
        combine(store.observe, failed, ::ReverseSwapState)
            .stateIn(scope, SharingStarted.Eagerly, ReverseSwapState())
    val history = store.history

    suspend fun quote(units: Int): ReverseSwapRecord {
        check(available)
        return driver(ReverseSwapTestnet.deployment).quote(units).also { failed.value = false }
    }

    suspend fun maximum(available: java.math.BigInteger): java.math.BigInteger {
        val cost =
            wallet.reverseCost(
                xyz.justzappit.railgun.RailgunReverseCostRequest(
                    available.toString(),
                    ReverseSwapTestnet.deployment.railgun
                )
            )
        val fee =
            available * cost.unshieldFeeBasisPoints.toBigInteger() /
                xyz.justzappit.railgun.RailgunReverseCost.FEE_DENOMINATOR
                    .toBigInteger()
        return available - fee
    }

    suspend fun review(index: Int) {
        indices.acceptanceLock.withLock { activeDriver().review(index) }
        resume(true)
    }

    suspend fun fund(index: Int) {
        try {
            activeDriver().fund(index)
        } finally {
            resume(true)
        }
    }

    suspend fun ready(index: Int) {
        try {
            activeDriver().ready(index)
        } finally {
            resume(true)
        }
    }

    suspend fun cancel(index: Int) {
        try {
            activeDriver().cancel(index)
        } finally {
            resume(true)
        }
    }

    suspend fun rescue(index: Int) {
        try {
            activeDriver().rescue(index)
        } finally {
            resume(true)
        }
    }

    suspend fun isUnderWay(): Boolean = available && store.active()?.underWay == true

    @Suppress("TooGenericExceptionCaught")
    suspend fun canRescue(index: Int): Boolean =
        try {
            activeDriver().canRescue(index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Twig.warn(e) { "Reverse refund availability check will retry" }
            false
        }

    suspend fun awaitSettled() {
        store.observe.first { it?.underWay != true }
    }

    suspend fun refresh() {
        try {
            val record = activeDriver().advance()
            failed.value = false
            notifyPhase(record)
        } finally {
            resume(true)
        }
    }

    fun resume(isForeground: Boolean) {
        if (!available) return
        synchronized(this) {
            if (job?.isActive != true) job = scope.launch { run() }
        }
        if (isForeground) scheduler.runNow() else scheduler.runIfIdle()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun run() {
        var previousPhase: xyz.justzappit.offramp.atomicswap.ReversePhase? = null
        while (isUnderWay()) {
            try {
                val record = activeDriver().advance()
                if (record?.phase != previousPhase) {
                    notifyPhase(record)
                    previousPhase = record?.phase
                }
                failed.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.warn(e) { "Reverse swap reconciliation will retry" }
                failed.value = true
            }
            delay(15.seconds)
        }
    }

    private fun notifyPhase(record: ReverseSwapRecord?) {
        when {
            record?.finished == true -> notifier.reverseFinished(record.phase.label())
            record?.phase == xyz.justzappit.offramp.atomicswap.ReversePhase.AWAITING_READY -> notifier.needsYou(true)
        }
    }

    private suspend fun activeDriver() = driver(checkNotNull(store.active()).deployment)

    private fun driver(deployment: ReverseDeployment): ReverseSwapDriver =
        synchronized(drivers) {
            check(available)
            drivers.getOrPut(deployment) {
                val rpc = BaseRpcClient(RpcHttpClient.create(), deployment.rpcUrl)
                ReverseSwapDriver(
                    deployment,
                    ReverseSwapClient(http, deployment),
                    ReverseSwapChainImpl(rpc, deployment),
                    keys,
                    reverseKeys,
                    zcash,
                    ReverseSwapFundingImpl(wallet, rpc, deployment),
                    indices,
                    store
                )
            }
        }
}
