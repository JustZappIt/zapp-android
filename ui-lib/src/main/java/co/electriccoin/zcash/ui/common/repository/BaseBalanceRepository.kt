package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.time.Duration.Companion.seconds

sealed interface BaseBalance {
    val loadedOrNull: Usdc6? get() = (this as? Loaded)?.balance

    data object Loading : BaseBalance

    data object Unavailable : BaseBalance

    data class Loaded(
        val balance: Usdc6,
    ) : BaseBalance
}

internal fun interface BaseUsdcReader {
    suspend fun balance(): Usdc6
}

/**
 * The smart account's USDC, read once for every screen that shows it. Five surfaces used to keep
 * private copies taken when their ViewModel was built, and a ViewModel behind a forward navigation
 * outlives the operation that moved the funds, so backing out to one showed the balance from before.
 */
interface BaseBalanceRepository {
    val balance: StateFlow<BaseBalance>

    /**
     * Re-reads under whoever is watching. For an operation that moves USDC while the screen that
     * reports it stays up; a screen re-entered later reads on subscription anyway.
     */
    fun invalidate()

    suspend fun refresh()

    fun reset()
}

internal class BaseBalanceRepositoryImpl(
    private val reader: BaseUsdcReader,
    applicationStateProvider: ApplicationStateProvider,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BaseBalanceRepository {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val current = MutableStateFlow<BaseBalance>(BaseBalance.Loading)
    private val reads = Mutex()

    override val balance: StateFlow<BaseBalance> = current.asStateFlow()

    init {
        scope.launch {
            combine(
                current.subscriptionCount.map { it > 0 },
                applicationStateProvider.isInForeground,
            ) { isObserved, isInForeground -> isObserved && isInForeground }
                .distinctUntilChanged()
                .collectLatest { isLive ->
                    if (!isLive) return@collectLatest
                    while (true) {
                        refresh()
                        delay(POLL_INTERVAL)
                    }
                }
        }
    }

    override fun invalidate() {
        if (current.subscriptionCount.value == 0) return
        scope.launch { refresh() }
    }

    /** Serialised rather than dropped: a read that follows a transfer is the one showing its result. */
    override suspend fun refresh() =
        reads.withLock {
            val read =
                runCatching { BaseBalance.Loaded(reader.balance()) }
                    .onFailure { Twig.warn(it) { "BaseBalanceRepository: read failed" } }
                    .getOrNull()
            current.update { read ?: it as? BaseBalance.Loaded ?: BaseBalance.Unavailable }
        }

    override fun reset() {
        current.update { BaseBalance.Loading }
    }

    private companion object {
        val POLL_INTERVAL = 30.seconds
    }
}
