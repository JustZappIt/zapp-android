// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.RailgunKeyProvider
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import co.electriccoin.zcash.ui.common.provider.ZcashNetworkProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunBalances
import xyz.justzappit.railgun.RailgunBroadcaster
import xyz.justzappit.railgun.RailgunEvent
import xyz.justzappit.railgun.RailgunFees
import xyz.justzappit.railgun.RailgunMerkletree
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunRelayedProof
import xyz.justzappit.railgun.RailgunReverseCost
import xyz.justzappit.railgun.RailgunReverseCostRequest
import xyz.justzappit.railgun.RailgunReverseRequest
import xyz.justzappit.railgun.RailgunReverseTransaction
import xyz.justzappit.railgun.RailgunSession
import xyz.justzappit.railgun.RailgunTransfer
import xyz.justzappit.railgun.RailgunWallet
import java.math.BigInteger
import kotlin.time.Clock
import kotlin.time.Instant

/** The Railgun wallet of the Zapp seed's Railgun mnemonic, on Sepolia in testnet builds; mainnet builds have none. */
interface RailgunWalletRepository {
    val state: StateFlow<RailgunWalletState>

    /** Starts and opens what isn't yet, then syncs. */
    suspend fun sync(): RailgunSync

    /** The fees the engine last started with, without waiting on it; else once it starts. */
    suspend fun fees(): RailgunFees

    /** What [broadcaster] charges to send [transfer]. Nothing is proved. */
    suspend fun broadcasterFee(
        transfer: RailgunTransfer,
        broadcaster: RailgunBroadcaster
    ): BigInteger

    /** Proves [transfer] for [broadcaster] to send, paying it [fee]. Nothing is sent. */
    suspend fun prove(
        transfer: RailgunTransfer,
        broadcaster: RailgunBroadcaster,
        fee: BigInteger
    ): RailgunRelayedProof

    suspend fun reverseCost(request: RailgunReverseCostRequest): RailgunReverseCost

    suspend fun prepareReverse(request: RailgunReverseRequest): RailgunReverseTransaction

    /** Stops the engine and deletes what it stored, for a wallet about to be wiped. */
    suspend fun reset()
}

/** Balances as a sync of the wallet at [address] found them. */
data class RailgunSync(
    val address: RailgunAddress,
    val balances: RailgunBalances,
    val at: Instant,
)

data class RailgunWalletState(
    val phase: Phase,
    val network: RailgunNetwork?,
    val address: RailgunAddress? = null,
    val fees: RailgunFees? = null,
    val sync: RailgunSync? = null,
    val utxoScan: RailgunEvent.Scan? = null,
    val txidScan: RailgunEvent.Scan? = null,
    val proof: RailgunEvent.Proof? = null,
    val error: String? = null,
) {
    enum class Phase { UNAVAILABLE, IDLE, STARTING, SYNCING, PROVING, READY, FAILED }
}

class RailgunWalletRepositoryImpl(
    railgunWallet: RailgunWallet,
    keyProvider: RailgunKeyProvider,
    mnemonicProvider: RailgunMnemonicProvider,
    zcashNetworkProvider: ZcashNetworkProvider,
    scope: CoroutineScope,
) : RailgunWalletRepository {
    private val network = if (zcashNetworkProvider() == ZcashNetwork.Testnet) RailgunNetwork.SEPOLIA else null
    private val mutableState =
        MutableStateFlow(
            RailgunWalletState(
                phase = if (network == null) RailgunWalletState.Phase.UNAVAILABLE else RailgunWalletState.Phase.IDLE,
                network = network,
            )
        )
    override val state: StateFlow<RailgunWalletState> = mutableState.asStateFlow()
    private val engine =
        network?.let { RailgunEngine(railgunWallet, keyProvider, mnemonicProvider, it, mutableState, scope) }

    init {
        if (engine != null) {
            scope.launch {
                railgunWallet.events.collect { event ->
                    if (event is RailgunEvent.Log) {
                        Twig.debug { "Railgun: ${event.message}" }
                    } else {
                        mutableState.update { it.after(event) }
                    }
                }
            }
            // A wallet deleted and replaced in this process must not stay open in the engine.
            scope.launch { mnemonicProvider.walletChanges.drop(1).collect { engine.forget() } }
        }
    }

    override suspend fun sync(): RailgunSync = withSession { mutableState.syncWith(it) }

    override suspend fun fees(): RailgunFees = state.value.fees ?: withSession { it.fees }

    override suspend fun broadcasterFee(
        transfer: RailgunTransfer,
        broadcaster: RailgunBroadcaster
    ): BigInteger = withSession { it.broadcasterFee(transfer, broadcaster) }

    override suspend fun prove(
        transfer: RailgunTransfer,
        broadcaster: RailgunBroadcaster,
        fee: BigInteger
    ): RailgunRelayedProof = proving { it.prove(transfer, broadcaster, fee) }

    override suspend fun reverseCost(request: RailgunReverseCostRequest): RailgunReverseCost =
        withSession { it.reverseCost(request) }

    override suspend fun prepareReverse(request: RailgunReverseRequest): RailgunReverseTransaction =
        withSession {
            mutableState.syncWith(it)
            it.prepareReverse(request)
        }

    override suspend fun reset() {
        engine?.reset()
    }

    private suspend fun <T> withSession(block: suspend (RailgunSession) -> T): T {
        val engine = checkNotNull(engine) { "this build has no Railgun network" }
        return mutableState.reported { engine.withSession(block) }
    }

    private suspend fun proving(prove: suspend (RailgunSession) -> RailgunRelayedProof) =
        withSession { session ->
            mutableState.update { it.copy(phase = RailgunWalletState.Phase.PROVING, proof = null) }
            prove(session).also { mutableState.update { it.copy(phase = RailgunWalletState.Phase.READY) } }
        }
}

private suspend fun MutableStateFlow<RailgunWalletState>.syncWith(session: RailgunSession): RailgunSync {
    // The scan lines describe this sync only, not whatever scan finished last.
    update { it.copy(phase = RailgunWalletState.Phase.SYNCING, utxoScan = null, txidScan = null) }
    val sync = RailgunSync(session.address, session.refresh(), Clock.System.now())
    update { it.copy(phase = RailgunWalletState.Phase.READY, sync = sync) }
    return sync
}

/** Runs [block], showing its failure, the engine's start included, on the state before passing it on. */
private suspend fun <T> MutableStateFlow<RailgunWalletState>.reported(block: suspend () -> T): T {
    update { it.copy(error = null) }
    return runCatching { block() }
        .onFailure { e ->
            if (e is CancellationException) return@onFailure
            Twig.warn { "Railgun wallet failed: ${e.message}" }
            update { it.copy(phase = RailgunWalletState.Phase.FAILED, error = e.message ?: e::class.simpleName) }
        }.getOrThrow()
}

private fun RailgunWalletState.after(event: RailgunEvent): RailgunWalletState =
    when (event) {
        is RailgunEvent.Scan -> {
            if (event.tree == RailgunMerkletree.UTXO) copy(utxoScan = event) else copy(txidScan = event)
        }

        is RailgunEvent.Proof -> {
            copy(proof = event)
        }

        is RailgunEvent.Log -> {
            this
        }
    }
