package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.PeerCashOutCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRepository
import co.electriccoin.zcash.ui.screen.swap.peer.PeerCashOutArgs
import co.electriccoin.zcash.ui.screen.swap.peer.order.PeerOrderArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PeerCashOutCheckpoint
import xyz.justzappit.offramp.peer.PeerCashOutId
import xyz.justzappit.offramp.peer.PeerCashOutOrchestrator
import xyz.justzappit.offramp.peer.PeerCashOutRequest
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerConfigProvider
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerDepositId
import xyz.justzappit.offramp.peer.PeerException
import xyz.justzappit.offramp.peer.PeerIndexerClient
import xyz.justzappit.offramp.peer.PeerMarket
import xyz.justzappit.offramp.peer.PeerMarketSnapshot
import xyz.justzappit.offramp.peer.PeerOrderSnapshot
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.PeerResumeAction

class NavigateToPeerCashOutUseCase(
    private val navigationRouter: NavigationRouter,
) {
    operator fun invoke(platform: PeerPlatform) {
        navigationRouter.forward(PeerCashOutArgs(platformWireName = platform.wireName))
    }
}

class NavigateToPeerOrderUseCase(
    private val navigationRouter: NavigationRouter,
) {
    operator fun invoke(depositId: PeerDepositId) {
        navigationRouter.forward(PeerOrderArgs(depositIdComposite = depositId.composite))
    }
}

class ObservePeerOrderUseCase(
    private val orchestrator: PeerCashOutOrchestrator,
) {
    operator fun invoke(depositId: PeerDepositId): Flow<PeerCashOutStatus> = orchestrator.observeOrder(depositId)
}

/**
 * Hands a cash-out to the app-scoped runner and returns the identity everything else hangs off:
 * the checkpoint it will write, and the progress screen that watches it.
 */
class StartPeerCashOutUseCase(
    private val repository: PeerCashOutRepository,
) {
    operator fun invoke(request: PeerCashOutRequest): PeerCashOutId =
        repository.newId().also { repository.start(it, request) }
}

/**
 * USDC promised to attempts that have not escrowed it yet. Subtracting this from the Base balance is
 * what stops three consecutive orders each spending the same coins: an amount is not gone from the
 * account until `createDeposit` is mined, so the raw balance says it is still there.
 */
class ObservePeerCommittedUsdcUseCase(
    private val repository: PeerCashOutRepository,
    private val checkpointStorage: PeerCashOutCheckpointStorageProvider,
) {
    operator fun invoke(): Flow<Usdc6> =
        combine(repository.runs, storedCheckpoints()) { runs, checkpoints ->
            val running = runs.filter { it.holdsFunds }
            val runningIds = running.map { it.id }.toSet()
            val dormant = checkpoints.filter { it.id !in runningIds && it.holdsUnescrowedFunds }
            (running.map { it.amount } + dormant.map { it.amount })
                .fold(Usdc6.ZERO) { total, amount -> total + amount }
        }

    // An undecodable blob must not take the amount screen down with it. Anything running in this
    // process is still counted, which is the part that can actually be double-spent right now.
    private fun storedCheckpoints(): Flow<List<PeerCashOutCheckpoint>> =
        checkpointStorage
            .observe()
            .catch { error ->
                Twig.warn(error) { "ObservePeerCommittedUsdcUseCase: checkpoint read failed" }
                emit(emptyList())
            }
}

/**
 * Teaches an unresolved checkpoint that its deposit already exists. Without this the amount it was
 * written for is subtracted from the balance twice — once by the escrow that holds it, once by
 * [ObservePeerCommittedUsdcUseCase] — and Available recovers only if the user happens to open the
 * attempt's progress screen and resume it by hand.
 *
 * Stamping the id rather than deleting the record keeps the attempt recoverable: its resume becomes
 * a read of the order it opened.
 */
class ReconcilePeerCheckpointsUseCase(
    private val peerConfigProvider: PeerConfigProvider,
    private val orchestrator: PeerCashOutOrchestrator,
    private val checkpointStorage: PeerCashOutCheckpointStorageProvider,
    private val repository: PeerCashOutRepository,
) {
    suspend operator fun invoke() {
        val stored = stored()
        // Attempts this process is driving own their own checkpoints; leave those to the runner. An
        // attempt that has failed or settled is driving nothing, and excluding it too is what left a
        // submission with an unknown outcome counted against the balance for the rest of the process.
        val unresolved = stored.filterNot { isDriving(it.id) }.filter(::isReconcilable)
        unresolved.forEach { checkpoint ->
            try {
                stamp(checkpoint.id, orchestrator.resolveCheckpoint(checkpoint))
            } catch (error: CancellationException) {
                throw error
            } catch (error: PeerException) {
                if (error.error.nothingEscrowed) {
                    retire(checkpoint.id)
                } else {
                    Twig.warn(error) { "ReconcilePeerCheckpointsUseCase: submission remains unresolved" }
                }
            } catch (error: Exception) {
                Twig.warn(error) { "ReconcilePeerCheckpointsUseCase: submission read failed" }
            }
        }
    }

    /**
     * Only a submitted deposit identity is reconcilable. A bridge/fresh record has not reached the
     * consuming call, and matching one by amount/payee would let a later identical order steal it.
     */
    private fun isReconcilable(checkpoint: PeerCashOutCheckpoint): Boolean =
        checkpoint.holdsUnescrowedFunds &&
            when (checkpoint.resumeAction) {
                is PeerResumeAction.ResolveSubmittedDeposit,
                is PeerResumeAction.ReconcileSubmission,
                -> true

                else -> false
            }

    /**
     * Re-read under the same conditions the decision was made on. The order read is a network round
     * trip, and a resume started inside it writes the transaction hashes this would otherwise
     * overwrite with a copy taken before they existed.
     *
     * The in-memory run is told too. An attempt that ended on an unknown submission is still holding
     * its amount against a balance the escrow has also taken, and nothing else in the process ever
     * learns which order it opened.
     */
    private suspend fun stamp(id: PeerCashOutId, depositId: PeerDepositId) {
        if (isDriving(id)) return
        runCatching { checkpointStorage.get(id) }
            .getOrNull()
            ?.takeIf(::isReconcilable)
            ?.let {
                checkpointStorage.store(it.copy(depositId = depositId))
                repository.onDepositReconciled(id, depositId)
            }
    }

    private suspend fun retire(id: PeerCashOutId) {
        if (isDriving(id)) return
        checkpointStorage.get(id)?.takeIf(::isReconcilable)?.let {
            checkpointStorage.clear(id)
            repository.onAttemptRetiredWithoutEscrow(id)
        }
    }

    private suspend fun stored(): List<PeerCashOutCheckpoint> {
        if (!peerConfigProvider.isAvailable) return emptyList()
        return runCatching { checkpointStorage.all() }
            .onFailure { Twig.warn(it) { "ReconcilePeerCheckpointsUseCase: checkpoint read failed" } }
            .getOrDefault(emptyList())
    }

    private fun isDriving(id: PeerCashOutId): Boolean = repository.runs.value.any { it.id == id && it.isDriving }
}

/**
 * Every open order for this seed, read from the chain. Peer is mainnet-only, so an unavailable
 * build reports none rather than failing.
 *
 * Filtered on the phase rather than the indexer's ACTIVE flag: withdrawing calls `removeFunds`,
 * which empties a deposit without closing it, so a drained order stays ACTIVE on chain forever and
 * would otherwise sit in the list reading "0 USDC on offer, waiting for a buyer".
 */
class GetPeerActiveOrdersUseCase(
    private val peerConfigProvider: PeerConfigProvider,
    private val orchestrator: PeerCashOutOrchestrator,
) {
    suspend operator fun invoke(): List<PeerOrderSnapshot> {
        if (!peerConfigProvider.isAvailable) return emptyList()
        return runCatching { orchestrator.activeOrders() }
            .getOrDefault(emptyList())
            .filterNot { it.phase.isFinished }
    }
}

/**
 * Every order this seed has ever opened, finished ones included, newest first. The active list is
 * what a "come back to this" surface needs; a history needs the closed ones too.
 */
class GetPeerOrderHistoryUseCase(
    private val peerConfigProvider: PeerConfigProvider,
    private val orchestrator: PeerCashOutOrchestrator,
) {
    suspend operator fun invoke(): List<PeerOrderSnapshot> {
        if (!peerConfigProvider.isAvailable) return emptyList()
        return runCatching { orchestrator.allOrders() }
            .onFailure { Twig.warn(it) { "GetPeerOrderHistoryUseCase: order read failed" } }
            .getOrDefault(emptyList())
            .sortedByDescending { it.creationBlockNumber ?: 0L }
    }
}

/**
 * Whether and when a pair actually fills, measured from the indexer before the user commits a
 * single satoshi. Fails open: a read failure means generic copy, never an invented estimate. The
 * snapshot is cached because the numbers move slowly and the amount screen is re-entered often.
 */
class GetPeerMarketSnapshotUseCase(
    private val indexerClient: PeerIndexerClient,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) {
    private val cache = mutableMapOf<String, CachedSnapshot>()
    private val mutex = Mutex()

    /**
     * Serialised, because the cache is one map shared by every screen that asks. Two entering at
     * once would both write it and both pay for the same pair of indexer reads.
     */
    suspend operator fun invoke(platform: PeerPlatform, currency: PeerCurrency): PeerMarketSnapshot? =
        mutex.withLock {
            val now = nowSeconds()
            val key = platform.wireName + CACHE_KEY_SEPARATOR + currency.code
            cache[key]?.takeIf { now - it.atSeconds < PeerMarket.CACHE_TTL_SECONDS }?.snapshot
                ?: read(platform, currency, key, now)
        }

    private suspend fun read(
        platform: PeerPlatform,
        currency: PeerCurrency,
        key: String,
        now: Long,
    ): PeerMarketSnapshot? {
        val queue =
            runCatching {
                indexerClient.queueSamples(
                    platform = platform,
                    currency = currency,
                    maturedBeforeSeconds = now - PeerMarket.MATURITY_SECONDS,
                )
            }.getOrNull()
        val fills =
            runCatching { indexerClient.fillSamples(platform, now - PeerMarket.WINDOW_SECONDS) }.getOrNull()
        if (queue == null || fills == null) return null
        val snapshot =
            PeerMarket.summarise(
                platform = platform,
                currency = currency,
                queueSamples = queue,
                fillSamples = fills,
                nowSeconds = now,
            )
        cache[key] = CachedSnapshot(snapshot = snapshot, atSeconds = now)
        return snapshot
    }

    private data class CachedSnapshot(
        val snapshot: PeerMarketSnapshot,
        val atSeconds: Long,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val CACHE_KEY_SEPARATOR = ":"
    }
}
