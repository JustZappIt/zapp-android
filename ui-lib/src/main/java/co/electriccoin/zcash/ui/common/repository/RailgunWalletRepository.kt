// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.RailgunKeyProvider
import co.electriccoin.zcash.ui.common.provider.ZcashNetworkProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.railgun.RailgunBalances
import xyz.justzappit.railgun.RailgunEvent
import xyz.justzappit.railgun.RailgunException
import xyz.justzappit.railgun.RailgunGasAccount
import xyz.justzappit.railgun.RailgunMerkletree
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunReverseCost
import xyz.justzappit.railgun.RailgunReverseCostRequest
import xyz.justzappit.railgun.RailgunReverseRequest
import xyz.justzappit.railgun.RailgunReverseTransaction
import xyz.justzappit.railgun.RailgunSent
import xyz.justzappit.railgun.RailgunWallet
import java.math.BigInteger
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * The Railgun wallet the Zapp seed opens, on Ethereum Sepolia for testnet builds; mainnet builds
 * don't have one yet. The engine and wallet start on the first call.
 */
interface RailgunWalletRepository {
    val state: StateFlow<RailgunWalletState>

    /** Starts and opens what isn't yet, then syncs. A call while another runs is dropped. */
    fun refresh()

    /** A testnet round trip, paid for and sent by the gas account in place of a broadcaster. */
    fun run(action: RailgunTestAction)

    /** Starts and opens what isn't yet, then syncs; waits for a call already running. */
    suspend fun reverseCost(params: RailgunReverseCostRequest): RailgunReverseCost

    suspend fun prepareReverse(params: RailgunReverseRequest): RailgunReverseTransaction

    suspend fun sync(): RailgunBalances

    /** Sends [amount] of [token] privately to a 0zk address, or out to a public one when [withdraw]. */
    suspend fun send(
        to: String,
        token: String,
        amount: BigInteger,
        withdraw: Boolean,
    ): RailgunSent
}

enum class RailgunTestAction { SHIELD, SEND_TO_SELF, WITHDRAW }

data class RailgunWalletState(
    val phase: Phase,
    val network: RailgunNetwork?,
    val address: String?,
    val balances: RailgunBalances?,
    val gasAccount: RailgunGasAccount?,
    val utxoScan: RailgunEvent.Scan?,
    val txidScan: RailgunEvent.Scan?,
    val proof: RailgunEvent.Proof?,
    val timings: List<Pair<String, Duration>>,
    val activity: List<Pair<RailgunTestAction, RailgunSent>>,
    val error: String?,
) {
    enum class Phase { UNAVAILABLE, IDLE, STARTING, OPENING, SYNCING, SENDING, READY, FAILED }
}

@Suppress("TooManyFunctions")
class RailgunWalletRepositoryImpl(
    private val railgunWallet: RailgunWallet,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val keyProvider: RailgunKeyProvider,
    zcashNetworkProvider: ZcashNetworkProvider,
) : RailgunWalletRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val network = if (zcashNetworkProvider() == ZcashNetwork.Testnet) RailgunNetwork.SEPOLIA else null
    private val mutableState =
        MutableStateFlow(
            RailgunWalletState(
                phase = if (network == null) RailgunWalletState.Phase.UNAVAILABLE else RailgunWalletState.Phase.IDLE,
                network = network,
                address = null,
                balances = null,
                gasAccount = null,
                utxoScan = null,
                txidScan = null,
                proof = null,
                timings = emptyList(),
                activity = emptyList(),
                error = null,
            )
        )
    override val state: StateFlow<RailgunWalletState> = mutableState.asStateFlow()

    @Volatile
    private var started = false

    @Volatile
    private var opened = false
    private val engineLock = Mutex()

    init {
        scope.launch {
            railgunWallet.events.collect { event ->
                when (event) {
                    is RailgunEvent.Log -> {
                        Twig.debug { "Railgun: ${event.message}" }
                    }

                    RailgunEvent.Disconnected -> {
                        started = false
                        opened = false
                    }

                    else -> {
                        mutableState.update { it.after(event) }
                    }
                }
            }
        }
        // A wallet deleted and replaced in this process must not stay open in the engine.
        scope.launch {
            persistableWalletProvider.persistableWallet
                .map { it?.seedPhrase?.joinToString()?.hashCode() }
                .distinctUntilChanged()
                .drop(1)
                .collect { forgetWallet() }
        }
    }

    override fun refresh() = launch { syncLocked() }

    override fun run(action: RailgunTestAction) =
        launch {
            open()
            mutableState.update { it.copy(phase = RailgunWalletState.Phase.SENDING, proof = null) }
            val address = checkNotNull(state.value.address)
            val gasAccount = checkNotNull(state.value.gasAccount).address
            val sent =
                when (action) {
                    RailgunTestAction.SHIELD -> railgunWallet.shieldBaseToken(SHIELD_AMOUNT)
                    RailgunTestAction.SEND_TO_SELF -> railgunWallet.transfer(address, WETH, SEND_AMOUNT)
                    RailgunTestAction.WITHDRAW -> railgunWallet.unshield(gasAccount, WETH, SEND_AMOUNT)
                }
            mutableState.update { it.copy(activity = it.activity + (action to sent)) }
            syncLocked()
        }

    override suspend fun reverseCost(params: RailgunReverseCostRequest): RailgunReverseCost =
        engineLock.withLock {
            open()
            railgunWallet.reverseCost(params)
        }

    override suspend fun prepareReverse(params: RailgunReverseRequest): RailgunReverseTransaction =
        engineLock.withLock {
            open()
            railgunWallet.refresh()
            railgunWallet.prepareReverse(params)
        }

    override suspend fun sync(): RailgunBalances {
        checkNotNull(network) { "this build has no Railgun network" }
        return engineLock.withLock { reported { syncLocked() } }
    }

    override suspend fun send(
        to: String,
        token: String,
        amount: BigInteger,
        withdraw: Boolean,
    ): RailgunSent {
        checkNotNull(network) { "this build has no Railgun network" }
        return engineLock.withLock {
            reported {
                open()
                mutableState.update { it.copy(phase = RailgunWalletState.Phase.SENDING, proof = null) }
                val sent =
                    if (withdraw) {
                        railgunWallet.unshield(to, token, amount)
                    } else {
                        railgunWallet.transfer(to, token, amount)
                    }
                // The send stands whatever the sync after it does.
                try {
                    syncLocked()
                } catch (e: RailgunException) {
                    mutableState.fail(e)
                }
                sent
            }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        if (network == null) return
        scope.launch {
            if (!engineLock.tryLock()) return@launch
            mutableState.update { it.copy(error = null) }
            try {
                block()
            } catch (e: RailgunException) {
                mutableState.fail(e)
            } catch (e: IllegalStateException) {
                mutableState.fail(e)
            } finally {
                engineLock.unlock()
            }
        }
    }

    /** Runs [block], showing its failure on the state before passing it on. */
    private suspend fun <T> reported(block: suspend () -> T): T {
        mutableState.update { it.copy(error = null) }
        return try {
            block()
        } catch (e: RailgunException) {
            mutableState.fail(e)
            throw e
        } catch (e: IllegalStateException) {
            mutableState.fail(e)
            throw e
        }
    }

    private suspend fun open() {
        val network = checkNotNull(network)
        if (!started) {
            mutableState.update { it.copy(phase = RailgunWalletState.Phase.STARTING) }
            timed("start") { railgunWallet.start(network, RPC_URLS.getValue(network), POI_NODE_URLS) }
            started = true
        }
        if (!opened) {
            mutableState.update { it.copy(phase = RailgunWalletState.Phase.OPENING) }
            val mnemonic = persistableWalletProvider.requirePersistableWallet().seedPhrase.joinToString()
            val key = keyProvider.encryptionKey()
            val address = timed("open") { railgunWallet.openWallet(key, mnemonic) }
            val gasAccount = railgunWallet.setGasAccount(keyProvider.gasAccountKey())
            Twig.info { "Railgun gas account: ${gasAccount.address}" }
            opened = true
            mutableState.update { it.copy(address = address, gasAccount = gasAccount) }
        }
    }

    private suspend fun syncLocked(): RailgunBalances {
        open()
        // The scan lines describe this sync only, not whatever scan finished last.
        mutableState.update { it.copy(phase = RailgunWalletState.Phase.SYNCING, utxoScan = null, txidScan = null) }
        val balances = timed("sync") { railgunWallet.refresh() }
        val gasAccount = railgunWallet.gasAccount()
        mutableState.update {
            it.copy(phase = RailgunWalletState.Phase.READY, balances = balances, gasAccount = gasAccount)
        }
        return balances
    }

    private suspend fun forgetWallet() =
        engineLock.withLock {
            if (started) railgunWallet.close()
            started = false
            opened = false
            mutableState.update {
                it.copy(
                    phase = RailgunWalletState.Phase.IDLE,
                    address = null,
                    balances = null,
                    gasAccount = null,
                    utxoScan = null,
                    txidScan = null,
                    proof = null,
                    activity = emptyList(),
                    error = null,
                )
            }
        }

    private suspend fun <T> timed(
        label: String,
        block: suspend () -> T
    ): T {
        val (value, duration) = measureTimedValue { block() }
        mutableState.update { it.copy(timings = (it.timings + (label to duration)).takeLast(MAX_TIMINGS)) }
        return value
    }

    private companion object {
        const val MAX_TIMINGS = 8
        val RPC_URLS = mapOf(RailgunNetwork.SEPOLIA to listOf("https://ethereum-sepolia-rpc.publicnode.com"))
        val POI_NODE_URLS = listOf("https://ppoi.fdi.network/")

        // Sepolia WETH, what shielding ETH wraps it into.
        const val WETH = "0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14"
        val SHIELD_AMOUNT: BigInteger = BigInteger.TEN.pow(16)
        val SEND_AMOUNT: BigInteger = BigInteger.TEN.pow(15)
    }
}

private fun RailgunWalletState.after(event: RailgunEvent): RailgunWalletState =
    when (event) {
        is RailgunEvent.Scan -> {
            if (event.tree == RailgunMerkletree.UTXO) copy(utxoScan = event) else copy(txidScan = event)
        }

        is RailgunEvent.Proof -> {
            copy(proof = event)
        }

        is RailgunEvent.Log, RailgunEvent.Disconnected -> {
            this
        }
    }

private fun MutableStateFlow<RailgunWalletState>.fail(e: Exception) {
    Twig.warn { "Railgun wallet failed: ${e.message}" }
    update { it.copy(phase = RailgunWalletState.Phase.FAILED, error = e.message ?: e::class.simpleName) }
}
