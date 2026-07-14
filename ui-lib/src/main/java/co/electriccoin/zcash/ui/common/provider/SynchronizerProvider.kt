package co.electriccoin.zcash.ui.common.provider

import android.content.Context
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.repository.ConfigurationRepository
// requires com.zodl.slipstream:slipstream-android — see docs/slipstream/INTEGRATION.md.
// Until that AAR is published this import does not resolve (the branch stays DO-NOT-MERGE).
import com.zodl.slipstream.SlipstreamSynchronizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface SynchronizerProvider {
    val error: StateFlow<SynchronizerError?>

    val synchronizer: StateFlow<Synchronizer?>

    /**
     * Get synchronizer and wait for it to be ready.
     */
    suspend fun getSynchronizer(): Synchronizer

    /**
     * Returns null if there is no persistable wallet, otherwise waits for the loaded synchronizer.
     */
    suspend fun getSynchronizerOrNull(): Synchronizer?

    suspend fun getVotingWalletDbPath(): String

    fun resetSynchronizer()
}

class SynchronizerProviderImpl(
    private val context: Context,
    private val walletCoordinator: WalletCoordinator,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val isExchangeRateEnabledStorageProvider: IsExchangeRateEnabledStorageProvider,
    private val configurationRepository: ConfigurationRepository,
) : SynchronizerProvider {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override val error = MutableStateFlow<SynchronizerError?>(null)

    // The one place the sync engine is chosen. The flag is one more input to the exact
    // teardown-rebuild shape the SDK's WalletCoordinator already runs for isTorEnabled: a flip
    // makes flatMapLatest cancel the current branch, fire its awaitClose (closing whichever engine
    // ran), and build the other against the same data.sqlite3 (no migration — both engines pin the
    // same zcash_client_sqlite schema generation). OFF delegates to walletCoordinator verbatim, so
    // the SDK path keeps its Tor, lockout, exchange-rate, and erase behavior untouched.
    @OptIn(ExperimentalCoroutinesApi::class)
    override val synchronizer: StateFlow<Synchronizer?> =
        combine(
            configurationRepository.isSlipstreamAvailable, // Boolean?  (null while config loads)
            persistableWalletProvider.persistableWallet // PersistableWallet?
        ) { slipstreamOn, wallet -> slipstreamOn to wallet }
            .flatMapLatest { (slipstreamOn, wallet) ->
                if (slipstreamOn == true && wallet != null) {
                    // ENGINE branch: build our adapter; never collect walletCoordinator.synchronizer,
                    // so the SDK's CompactBlockProcessor is never constructed. The factory call is
                    // WalletCoordinator's Synchronizer.new(...) with one token changed.
                    channelFlow<Synchronizer?> {
                        val closeable: CloseableSynchronizer =
                            SlipstreamSynchronizer.new(
                                context = context,
                                zcashNetwork = wallet.network,
                                lightWalletEndpoint = wallet.endpoint,
                                birthday = wallet.birthday,
                                setup =
                                    AccountCreateSetup(
                                        accountName = context.getString(R.string.accounts_zashi),
                                        keySource = "zashi",
                                        seed = FirstClassByteArray(wallet.seedPhrase.toByteArray())
                                    ),
                                walletInitMode = wallet.walletInitMode,
                                isTorEnabled = isTorEnabledStorageProvider.observe().first() == true,
                                isExchangeRateEnabled = isExchangeRateEnabledStorageProvider.observe().first() == true
                            )
                        val pipeline = initializeErrorHandling(closeable)
                        launch {
                            pipeline.collect { new ->
                                error.update { new }
                            }
                        }
                        send(closeable)
                        awaitClose {
                            closeable.onProcessorErrorHandler = null
                            closeable.onProcessorErrorResolved = null
                            closeable.onSetupErrorHandler = null
                            closeable.onChainErrorHandler = null
                            closeable.close() // teardown symmetric with WalletCoordinator.awaitClose
                        }
                    }
                } else {
                    // SDK branch: verbatim delegation (Tor, lockout, exchange-rate all preserved).
                    walletCoordinator.synchronizer.flatMapLatest { synchronizer ->
                        channelFlow {
                            if (synchronizer != null) {
                                val pipeline = initializeErrorHandling(synchronizer)

                                launch {
                                    pipeline.collect { new ->
                                        error.update { new }
                                    }
                                }
                            }

                            send(synchronizer)
                            awaitClose {
                                synchronizer?.onProcessorErrorHandler = null
                                synchronizer?.onProcessorErrorResolved = null
                                synchronizer?.onSetupErrorHandler = null
                                synchronizer?.onChainErrorHandler = null
                            }
                        }
                    }
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.Lazily,
                initialValue = null
            )

    override suspend fun getSynchronizer(): Synchronizer =
        withContext(Dispatchers.IO) {
            synchronizer
                .filterNotNull()
                .first()
        }

    override suspend fun getSynchronizerOrNull(): Synchronizer? =
        withContext(Dispatchers.IO) {
            if (persistableWalletProvider.getPersistableWallet() == null) {
                null
            } else {
                getSynchronizer()
            }
        }

    override suspend fun getVotingWalletDbPath(): String =
        getSynchronizer().getWalletDbPathForVoting()

    override fun resetSynchronizer() {
        walletCoordinator.resetSynchronizer()
    }

    private fun initializeErrorHandling(synchronizer: Synchronizer): Flow<SynchronizerError?> {
        val pipeline = MutableStateFlow<SynchronizerError?>(null)

        // synchronizer.onCriticalErrorHandler = { error ->
        //     Twig.error { "WALLET - Error Critical: $error" }
        //     pipeline.update { SynchronizerError.Critical(error)}
        //     false
        // }
        synchronizer.onProcessorErrorHandler = { error ->
            Twig.error { "WALLET - Error Processor: $error" }
            pipeline.update { SynchronizerError.Processor(error) }
            true
        }
        synchronizer.onProcessorErrorResolved = {
            Twig.error { "WALLET - Processor error resolved" }
            pipeline.update { null }
        }
        synchronizer.onSetupErrorHandler = { error ->
            Twig.error { "WALLET - Error Setup: $error" }
            pipeline.update { SynchronizerError.Setup(error) }
            false
        }
        synchronizer.onChainErrorHandler = { x, y ->
            Twig.error { "WALLET - Error Chain: $x, $y" }
            pipeline.update { SynchronizerError.Chain(x, y) }
        }

        return pipeline
    }
}
