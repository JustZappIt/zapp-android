// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapKeysImpl
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.atomicswap.AtomicSwapException
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.railgun.RailgunBalanceBucket
import xyz.justzappit.railgun.RailgunBalances
import xyz.justzappit.railgun.RailgunException
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunTokenAmount
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
            val assets = mutableMapOf<String, PrivateUsdAsset>()
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
)

/**
 * The Railgun balance as private USD. The engine takes up to a minute to sync from cold, so the last
 * balance is kept, encrypted and tied to the wallet it belongs to, and refreshed in the background.
 */
interface PrivateUsdBalanceRepository {
    val state: StateFlow<PrivateUsdBalanceState>

    /** [state] that, while collected, keeps refreshing: often while a payout is on its way in. */
    fun observe(): Flow<PrivateUsdBalanceState>

    fun refresh(maxAge: Duration = Duration.ZERO)
}

class PrivateUsdBalanceRepositoryImpl(
    private val railgunWalletRepository: RailgunWalletRepository,
    private val atomicSwapRepository: AtomicSwapRepository,
    private val keys: AtomicSwapKeysImpl,
    persistableWalletProvider: PersistableWalletProvider,
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : PrivateUsdBalanceRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val network = railgunWalletRepository.state.value.network
    private val cache = EncryptedJsonStore(encryptedPreferenceProvider, CACHE_KEY, Cache.serializer())
    private val mutableState = MutableStateFlow(PrivateUsdBalanceState())
    override val state: StateFlow<PrivateUsdBalanceState> = mutableState.asStateFlow()

    init {
        if (network != null) {
            scope.launch {
                persistableWalletProvider.persistableWallet
                    .map { it?.seedPhrase?.joinToString()?.hashCode() }
                    .distinctUntilChanged()
                    .collect { load(network) }
            }
            scope.launch {
                railgunWalletRepository.state
                    .map { it.balances }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { save(network, it) }
            }
        }
    }

    override fun observe(): Flow<PrivateUsdBalanceState> =
        channelFlow {
            launch { keepFresh() }
            state.collect { send(it) }
        }

    override fun refresh(maxAge: Duration) {
        val current = state.value
        val fresh = current.updatedAt?.let { Clock.System.now() - it < maxAge } == true
        if (network == null || current.isRefreshing || fresh) return
        mutableState.update { it.copy(isRefreshing = true) }
        scope.launch {
            val failed =
                try {
                    railgunWalletRepository.sync()
                    false
                } catch (e: RailgunException) {
                    Twig.warn { "Private USD: refresh failed, ${e.message}" }
                    true
                } catch (e: IllegalStateException) {
                    Twig.warn { "Private USD: refresh failed, ${e.message}" }
                    true
                }
            mutableState.update { it.copy(isRefreshing = false, refreshFailed = failed) }
        }
    }

    private suspend fun keepFresh() {
        while (true) {
            refresh(if (isArriving()) ARRIVING_POLL else STALE_AFTER)
            delay(ARRIVING_POLL)
        }
    }

    // Something is in screening, or a payout landed since the last sync.
    private fun isArriving(): Boolean {
        val current = state.value
        val record = atomicSwapRepository.state.value.record
        val paidAt =
            record
                ?.finishedAt
                ?.takeIf { record.outcome == AtomicSwapOutcome.Paid }
                ?.let(Instant::fromEpochSeconds)
        val payoutUnseen =
            paidAt != null &&
                Clock.System.now() - paidAt < PAYOUT_WATCH &&
                current.updatedAt?.let { it < paidAt + RPC_LAG } != false
        return payoutUnseen || (current.balances?.arriving?.signum() ?: 0) > 0
    }

    private suspend fun load(network: RailgunNetwork) {
        val cached =
            try {
                cache.get()?.takeIf { it.address == address() }
            } catch (e: StoreCorruptedException) {
                Twig.warn(e) { "Private USD: the cached balance is unreadable" }
                null
            }
        mutableState.value =
            PrivateUsdBalanceState(
                balances = cached?.let { PrivateUsdBalances.of(network, it.byBucket()) },
                updatedAt = cached?.let { Instant.fromEpochMilliseconds(it.updatedAt) },
            )
    }

    private suspend fun save(
        network: RailgunNetwork,
        balances: RailgunBalances
    ) {
        val address = address() ?: return
        val buckets =
            balances.byBucket.mapKeys { it.key.name }.mapValues { (_, tokens) ->
                tokens.filter { it.amount.signum() > 0 }.map { CachedAmount(it.token, it.amount.toString()) }
            }
        val now = Clock.System.now()
        cache.set(Cache(address, now.toEpochMilliseconds(), buckets))
        val decoded = PrivateUsdBalances.of(network, balances.byBucket)
        mutableState.update { it.copy(balances = decoded, updatedAt = now, refreshFailed = false) }
    }

    private suspend fun address(): String? =
        try {
            keys.withKey(0) { AtomicSwap.railgunAddress(it.seed) }
        } catch (e: AtomicSwapException) {
            Twig.warn(e) { "Private USD: no Railgun address" }
            null
        } catch (e: IllegalStateException) {
            Twig.info { "Private USD: no wallet yet, ${e.message}" }
            null
        }

    @Serializable
    private data class Cache(
        val address: String,
        val updatedAt: Long,
        val buckets: Map<String, List<CachedAmount>>,
    ) {
        fun byBucket(): Map<RailgunBalanceBucket, List<RailgunTokenAmount>> =
            buckets
                .mapNotNull { (name, amounts) ->
                    RailgunBalanceBucket.entries.firstOrNull { it.name == name }?.let { bucket ->
                        bucket to amounts.map { RailgunTokenAmount(it.token, BigInteger(it.amount)) }
                    }
                }.toMap()
    }

    @Serializable
    private data class CachedAmount(
        val token: String,
        val amount: String,
    )

    private companion object {
        const val CACHE_KEY = "private_usd_balances_v1"
        val ARRIVING_POLL = 30.seconds
        val STALE_AFTER = 5.minutes
        val PAYOUT_WATCH = 2.hours
        val RPC_LAG = 30.seconds
    }
}
