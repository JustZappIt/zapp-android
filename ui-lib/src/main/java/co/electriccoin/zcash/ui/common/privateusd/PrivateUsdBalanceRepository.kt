// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import co.electriccoin.zcash.ui.common.repository.RailgunSync
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunBalanceBucket
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunScanStatus
import xyz.justzappit.railgun.RailgunTokenAmount
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.exp
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

data class PrivateUsdAsset(
    val token: PrivateUsdToken,
    val available: BigInteger = BigInteger.ZERO,
    val arriving: BigInteger = BigInteger.ZERO,
    val blocked: BigInteger = BigInteger.ZERO,
    val processing: BigInteger = BigInteger.ZERO,
)

data class PrivateUsdBalances(
    val assets: List<PrivateUsdAsset>
) {
    val available: BigDecimal = dollars { it.available }
    val arriving: BigDecimal = dollars { it.arriving }
    val blocked: BigDecimal = dollars { it.blocked }
    val processing: BigDecimal = dollars { it.processing }
    val total: BigDecimal = available + arriving + blocked + processing

    /** What the balance shows at a glance: what can be spent, and what's only in flight after a send. */
    val spendable: BigDecimal = available + processing

    /** Screening refused some of it. */
    val isBlocked: Boolean get() = blocked.signum() > 0

    private fun dollars(amount: (PrivateUsdAsset) -> BigInteger) =
        assets
            .filter { it.token.isDollar }
            .fold(BigDecimal.ZERO) { sum, asset -> sum + amount(asset).toDecimal(asset.token.decimals) }

    companion object {
        /** Known tokens only, by screening status; the SDK lists every bucket of every token, zeros too. */
        fun of(
            network: RailgunNetwork,
            byBucket: Map<RailgunBalanceBucket, List<RailgunTokenAmount>>
        ): PrivateUsdBalances {
            val assets = mutableMapOf<Address, PrivateUsdAsset>()
            byBucket.forEach { (bucket, amounts) ->
                amounts.forEach { held ->
                    val token = PrivateUsdTokens.find(network, held.token) ?: return@forEach
                    val asset = assets[token.address] ?: PrivateUsdAsset(token)
                    assets[token.address] = asset.plus(bucket, held.amount)
                }
            }
            return PrivateUsdBalances(PrivateUsdTokens.of(network).mapNotNull { assets[it.address] })
        }

        private fun PrivateUsdAsset.plus(
            bucket: RailgunBalanceBucket,
            amount: BigInteger
        ): PrivateUsdAsset =
            when (bucket) {
                RailgunBalanceBucket.SPENDABLE -> {
                    copy(available = available + amount)
                }

                RailgunBalanceBucket.SHIELD_PENDING -> {
                    copy(arriving = arriving + amount)
                }

                RailgunBalanceBucket.SHIELD_BLOCKED -> {
                    copy(blocked = blocked + amount)
                }

                RailgunBalanceBucket.PROOF_SUBMITTED,
                RailgunBalanceBucket.MISSING_INTERNAL_POI,
                RailgunBalanceBucket.MISSING_EXTERNAL_POI -> {
                    copy(processing = processing + amount)
                }

                RailgunBalanceBucket.SPENT -> {
                    this
                }
            }
    }
}

data class PrivateUsdBalanceState(
    /** Null until a sync or the cache has something to show. */
    val balances: PrivateUsdBalances? = null,
    val updatedAt: Instant? = null,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    /** How far the wallet's first sync has come, while there's nothing to show yet. */
    val firstSyncPercent: Int? = null,
) {
    /** What of [token] can be spent now: none once synced without it, null until known. */
    fun available(token: PrivateUsdToken): BigInteger? =
        balances?.let { known -> known.assets.firstOrNull { it.token == token }?.available ?: BigInteger.ZERO }
}

/** The Railgun balance as private USD. A wallet's first sync reads all of Railgun's history, so the last is cached. */
interface PrivateUsdBalanceRepository {
    val state: StateFlow<PrivateUsdBalanceState>

    /** [state] that keeps refreshing while collected, often while a payout is on its way in. */
    fun observe(): Flow<PrivateUsdBalanceState>

    /** [observe] for outside Private USD's screens, which starts the engine only once Private USD is in use. */
    fun observeIfUsed(): Flow<PrivateUsdBalanceState>

    fun refresh(maxAge: Duration = Duration.ZERO)
}

class PrivateUsdBalanceRepositoryImpl(
    private val railgunWalletRepository: RailgunWalletRepository,
    private val atomicSwapRepository: AtomicSwapRepository,
    private val railgunMnemonicProvider: RailgunMnemonicProvider,
    private val sendLog: PrivateUsdSendLog,
    private val senders: PrivateUsdSenders,
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val scope: CoroutineScope,
    private val clock: Clock,
) : PrivateUsdBalanceRepository {
    private val network = railgunWalletRepository.state.value.network
    private val cache = PrivateUsdBalanceCache(encryptedPreferenceProvider)
    private val mutableState = MutableStateFlow(PrivateUsdBalanceState())
    override val state: StateFlow<PrivateUsdBalanceState> = mutableState.asStateFlow()
    private val retries = Retries(clock)

    // Guards the wallet the state belongs to, and what's shown for it.
    private val walletLock = Mutex()
    private var wallet: RailgunAddress? = null
    private var shown: RailgunSync? = null

    // Guarded by `this`.
    private var refreshing: Job? = null

    @Volatile
    private var inUse = false

    init {
        if (network != null) {
            scope.launch { railgunMnemonicProvider.walletChanges.collect { load(network) } }
            // Syncs made for sending, converting or debugging count too, and each settles the sends still out.
            scope.launch {
                railgunWalletRepository.state
                    .mapNotNull { it.sync }
                    .distinctUntilChanged()
                    .collect {
                        show(network, it)
                        senders.current?.reconcile()
                    }
            }
            scope.launch { showFirstSync() }
        }
    }

    // Between the engine's reports the number creeps, so it's worked out again now and then.
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun showFirstSync() {
        val firstSync = FirstSyncProgress()
        railgunWalletRepository.state
            .transformLatest { railgun ->
                emit(firstSync.percent(railgun))
                while (firstSync.isCreeping) {
                    delay(FIRST_SYNC_TICK)
                    emit(firstSync.percent(railgun))
                }
            }.collect { percent ->
                mutableState.update { balance ->
                    balance.copy(firstSyncPercent = percent.takeIf { balance.balances == null })
                }
            }
    }

    override fun observe(): Flow<PrivateUsdBalanceState> = keptFresh(ifUsed = false)

    override fun observeIfUsed(): Flow<PrivateUsdBalanceState> = keptFresh(ifUsed = true)

    override fun refresh(maxAge: Duration) {
        val network = network ?: return
        synchronized(this) {
            val updatedAt = state.value.updatedAt
            val isFresh = updatedAt != null && clock.now() - updatedAt < maxAge
            // A refresh asked for outright doesn't wait out the failures before it.
            val isBackingOff = maxAge > Duration.ZERO && !retries.isDue()
            if (refreshing?.isActive == true || isFresh || isBackingOff) return
            refreshing = scope.launch { refreshNow(network) }
        }
    }

    private fun keptFresh(ifUsed: Boolean): Flow<PrivateUsdBalanceState> =
        channelFlow {
            if (network != null) {
                launch {
                    while (true) {
                        if (!ifUsed || isInUse()) {
                            val isArriving = isArriving(state.value, atomicSwapRepository.state.value, clock.now())
                            refresh(if (isArriving) ARRIVING_POLL else STALE_AFTER)
                        }
                        delay(ARRIVING_POLL)
                    }
                }
            }
            state.collect { send(it) }
        }

    // Converted to, sent from, or holding something: once it is, it stays so for this wallet.
    private suspend fun isInUse(): Boolean {
        if (!inUse) {
            val holds = state.value.balances?.let { it.total.signum() > 0 } == true
            inUse = holds || atomicSwapRepository.history.first().isNotEmpty() || !sendLog.observe.first().isEmpty
        }
        return inUse
    }

    private suspend fun refreshNow(network: RailgunNetwork) {
        mutableState.update { it.copy(isRefreshing = true, refreshFailed = false) }
        try {
            // With nothing shown yet, this may be the wallet's first sync, which reads all of Railgun's history.
            val sync = syncWithin(if (state.value.balances == null) FIRST_SYNC_TIMEOUT else REFRESH_TIMEOUT)
            sync?.let { show(network, it) }
            retries.record(succeeded = sync != null)
            mutableState.update { it.copy(refreshFailed = sync == null) }
        } finally {
            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    private suspend fun syncWithin(timeout: Duration): RailgunSync? =
        try {
            withTimeoutOrNull(timeout) { railgunWalletRepository.sync() }
                .also { if (it == null) Twig.warn { "Private USD: the refresh took over $timeout" } }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            Twig.warn(ignored) { "Private USD: refresh failed" }
            null
        }

    private suspend fun load(network: RailgunNetwork) =
        walletLock.withLock {
            wallet = null
            shown = null
            inUse = false
            retries.record(succeeded = true)
            val address = railgunMnemonicProvider.addressOrNull()
            val cached = address?.let { cache.read(it, network) }
            wallet = address
            mutableState.update {
                it.copy(balances = cached?.balances, updatedAt = cached?.updatedAt, refreshFailed = false)
            }
        }

    // Only a sync of the wallet in use counts, whichever wallet was open when it ran.
    private suspend fun show(
        network: RailgunNetwork,
        sync: RailgunSync
    ) = walletLock.withLock {
        if (sync.address != wallet || sync == shown) return@withLock
        shown = sync
        cache.write(sync)
        val balances = PrivateUsdBalances.of(network, sync.balances.byBucket)
        mutableState.update { it.copy(balances = balances, updatedAt = sync.at, refreshFailed = false) }
    }

    private companion object {
        val ARRIVING_POLL = 30.seconds
        val STALE_AFTER = 5.minutes
        val REFRESH_TIMEOUT = 5.minutes
        val FIRST_SYNC_TIMEOUT = 2.hours
        val FIRST_SYNC_TICK = 3.seconds
    }
}

/** Automatic refreshes wait longer after each failure in a row. */
private class Retries(
    private val clock: Clock,
) {
    private var failures = 0
    private var dueAt: Instant? = null

    @Synchronized
    fun isDue(): Boolean = dueAt?.let { clock.now() >= it } != false

    @Synchronized
    fun record(succeeded: Boolean) {
        failures = if (succeeded) 0 else failures + 1
        dueAt = if (succeeded) null else clock.now() + FIRST_RETRY * (1 shl (failures - 1).coerceAtMost(MAX_DOUBLINGS))
    }

    private companion object {
        val FIRST_RETRY = 30.seconds
        const val MAX_DOUBLINGS = 5
    }
}

/** Something is in screening, or a payout to this wallet landed since the last sync. */
internal fun isArriving(
    balance: PrivateUsdBalanceState,
    swap: AtomicSwapState,
    now: Instant
): Boolean {
    val paidAt =
        swap.record
            ?.end
            ?.takeIf { it.outcome == AtomicSwapOutcome.Paid }
            ?.let { Instant.fromEpochSeconds(it.at) }
    val payoutUnseen =
        paidAt != null &&
            now - paidAt < PAYOUT_WATCH &&
            balance.updatedAt?.let { it < paidAt + RPC_LAG } != false
    return payoutUnseen || (balance.balances?.arriving?.signum() ?: 0) > 0
}

/**
 * A first sync's progress, in percent of the time one takes: Railgun's notes, then the transaction ids its screening
 * needs. The engine reports the notes at milestones that can be minutes apart, so between two the number creeps toward
 * the next one's, slower the nearer it gets, without passing it. The ids come in batches whose progress each starts
 * over, so every batch covers part of what's left and the number never goes back.
 */
internal class FirstSyncProgress(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private var idBatches = 0
    private var lastIds = 0f
    private var lastNotes = -1f
    private var notesSince = timeSource.markNow()

    /** Whether the number can move before the engine's next report, so is worth working out again. */
    var isCreeping = false
        private set

    fun percent(railgun: RailgunWalletState): Int? {
        val notes = railgun.utxoScan
        val ids = railgun.txidScan
        isCreeping = false
        return when {
            notes == null -> {
                null
            }

            notes.status != RailgunScanStatus.COMPLETE -> {
                idBatches = 0
                lastIds = 0f
                notesPercent(notes.progress.coerceIn(0f, 1f))
            }

            ids == null -> {
                NOTES_PERCENT
            }

            ids.status == RailgunScanStatus.COMPLETE -> {
                LAST_PERCENT
            }

            else -> {
                if (ids.progress < lastIds) idBatches += 1
                lastIds = ids.progress
                val left = ID_BATCH_LEFT.pow(idBatches + ids.progress.coerceIn(0f, 1f))
                LAST_PERCENT - ((LAST_PERCENT - NOTES_PERCENT) * left).toInt()
            }
        }
    }

    private fun notesPercent(progress: Float): Int {
        if (progress != lastNotes) {
            lastNotes = progress
            notesSince = timeSource.markNow()
        }
        val next = NOTES_STEPS.indexOfFirst { it.at > progress }
        return when (next) {
            -1 -> {
                NOTES_PERCENT
            }

            0 -> {
                0
            }

            else -> {
                val from = NOTES_STEPS[next - 1]
                val to = NOTES_STEPS[next]
                val done = (progress - from.at) / (to.at - from.at)
                val reported = from.percent + (to.percent - from.percent) * done
                val wait = notesSince.elapsedNow() / (from.takes * (1 - done.toDouble()) / 2)
                val crept = reported + (to.percent - reported) * (1 - exp(-wait))
                isCreeping = crept.toInt() + 1 < to.percent
                crept.toInt()
            }
        }
    }

    private companion object {
        // The engine's notes milestones, each with how long the next one took on a Galaxy A35 over Wi-Fi.
        val NOTES_STEPS =
            notesSteps(
                0.03f to 5.seconds, // loading the network
                0.10f to 6.minutes, // downloading Railgun's history
                0.15f to 7.minutes, // saving its unshields
                0.25f to 4.minutes, // and nullifiers
                0.30f to 16.minutes, // building its trees
                0.40f to 5.seconds,
                0.50f to 5.seconds, // catching up with the chain
                0.70f to 6.minutes, // finding this wallet's notes
                0.97f to 5.seconds,
                1f to Duration.ZERO,
            )
    }
}

/** One of the engine's notes milestones, with how long the next one takes and where on the way it comes. */
private class NotesStep(
    val at: Float,
    val takes: Duration,
    val percent: Double,
)

private fun notesSteps(vararg steps: Pair<Float, Duration>): List<NotesStep> {
    val total = steps.fold(Duration.ZERO) { sum, (_, takes) -> sum + takes }
    var before = Duration.ZERO
    return steps.map { (at, takes) ->
        NotesStep(at, takes, NOTES_PERCENT * (before / total)).also { before += takes }
    }
}

internal suspend fun RailgunMnemonicProvider.addressOrNull(): RailgunAddress? =
    try {
        address()
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Exception) {
        Twig.info { "Private USD: no Railgun wallet, ${ignored.message}" }
        null
    }

private val PAYOUT_WATCH = 2.hours
private val RPC_LAG = 30.seconds

// On Ethereum the notes take about 60% of a first sync, and each of its 15 or so id batches leaves 85% of the rest.
private const val NOTES_PERCENT = 60
private const val LAST_PERCENT = 99
private const val ID_BATCH_LEFT = 0.85f
