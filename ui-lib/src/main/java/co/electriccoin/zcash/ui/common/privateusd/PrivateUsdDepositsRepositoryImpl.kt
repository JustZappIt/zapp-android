// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployments
import co.electriccoin.zcash.ui.common.backgroundScope
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import co.electriccoin.zcash.ui.common.repository.ConfigurationRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.configuration.ConfigurationEntries
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.deposits.DepositPass
import xyz.justzappit.offramp.deposits.DepositRecord
import xyz.justzappit.offramp.deposits.DepositRescuable
import xyz.justzappit.offramp.deposits.DepositState
import xyz.justzappit.offramp.deposits.DepositTerms
import xyz.justzappit.offramp.deposits.DepositTermsRead
import xyz.justzappit.offramp.deposits.Depositor
import xyz.justzappit.offramp.deposits.DepositsChain
import xyz.justzappit.offramp.deposits.DepositsClient
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Deposits as Zapp runs them: [Depositor]'s passes, only while Zapp is open and unlocked, as Veylor has it. A pass runs
 * as Zapp comes to the foreground unlocked, as the Shielded USD page or the USD tab asks, after each sync of the
 * balance, then as the last one said: every 10 s while a deposit is posted, at its retry while one waits, in a minute
 * while an address holding enough wasn't signed for. Shielding is automatic, with nothing for the user to approve, and
 * new deposits are signed only while Shielded USD is switched on; one under way goes on regardless.
 */
class PrivateUsdDepositsRepositoryImpl(
    private val deployment: AtomicSwapDeployment,
    private val depositor: Depositor,
    private val store: PrivateUsdDepositStore,
    railgunWalletRepository: RailgunWalletRepository,
    // Built only once a deposit lands: building it sets up the conversions and their stores.
    private val balanceRepository: Lazy<PrivateUsdBalanceRepository>,
    private val configurationRepository: ConfigurationRepository,
    private val railgunMnemonicProvider: RailgunMnemonicProvider,
    private val scope: CoroutineScope,
    private val clock: Clock,
) : PrivateUsdDepositsRepository {
    private val token: PrivateUsdToken =
        checkNotNull(
            deployment.usdcDeposits
                ?.let { PrivateUsdTokens.find(deployment.railgunNetwork, it.token) }
                ?.takeIf { it.isDollar },
        ) { "deposits are in a token that isn't a known dollar" }
    private val mutableState = MutableStateFlow<PrivateUsdDeposits?>(PrivateUsdDeposits(token, address = null))
    override val state: StateFlow<PrivateUsdDeposits?> = mutableState.asStateFlow()
    override val history: Flow<List<DepositRecord>> = store.observe

    private val isUnlocked = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // The last terms that read, shown while a read fails, so the fees don't flicker with the network.
    @Volatile
    private var lastTerms: DepositTerms? = null

    // Guarded by `this`.
    private var passes: Job? = null

    init {
        start()
        // A wallet deleted and replaced in this process starts over, its addresses unknown until a pass, and the passes
        // a reset stopped start again once the new wallet is there.
        scope.launch {
            railgunMnemonicProvider.walletChanges.drop(1).collect {
                lastTerms = null
                mutableState.value = PrivateUsdDeposits(token, address = null)
                if (railgunMnemonicProvider.hasWallet()) start()
                wake.trySend(Unit)
            }
        }
        // After each sync of the balance, whichever screen asked for it.
        scope.launch {
            railgunWalletRepository.state
                .mapNotNull { it.sync }
                .distinctUntilChanged()
                .collect { wake.trySend(Unit) }
        }
    }

    override fun refresh() {
        wake.trySend(Unit)
    }

    override fun onAppUnlocked(isUnlocked: Boolean) {
        val wasUnlocked = this.isUnlocked.value
        this.isUnlocked.value = isUnlocked
        if (isUnlocked && !wasUnlocked) wake.trySend(Unit)
    }

    override suspend fun rescuable(): List<DepositRescuable> = depositor.rescuable()

    override suspend fun rescue(index: Int) {
        depositor.rescue(index)
        wake.trySend(Unit)
    }

    override suspend fun ownAddresses(): Set<Address> = depositor.ownAddresses()

    // Stopped until another wallet replaces this one: a pass while the wallet is wiped would read its records as
    // they're cleared, post its kept requests again, and write the records back for the next wallet to show and
    // wait on.
    override suspend fun reset() {
        synchronized(this) { passes.also { passes = null } }?.cancelAndJoin()
        lastTerms = null
        mutableState.value = PrivateUsdDeposits(token, address = null)
    }

    private fun start() {
        synchronized(this) {
            if (passes != null) return
            passes =
                scope.launch {
                    while (true) {
                        isUnlocked.first { it }
                        // What asked meanwhile is this pass.
                        wake.tryReceive()
                        val next = passNow()
                        withTimeoutOrNull(next ?: Duration.INFINITE) { wake.receive() }
                    }
                }
        }
    }

    // When to look again; none until something asks. A pass that can't run at all, with a store that doesn't read or
    // no wallet, leaves what's shown, and waits for the next ask.
    private suspend fun passNow(): Duration? =
        runCatching {
            val underWay =
                store
                    .records()
                    .filterNot { it.state.isFinished }
                    .map { it.id }
                    .toSet()
            val pass = depositor.pass(signNew = isShieldedUsdOn())
            show(pass)
            // The balance shows a deposit as arriving once a sync finds its note.
            if (pass.records.any { it.id in underWay && it.state == DepositState.LANDED }) {
                balanceRepository.value.refresh()
            }
            pass.nextPoll
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Twig.warn(e) { "Private USD: no pass of the deposit addresses" }
            mutableState.value = mutableState.value?.let { it.copy(isAddressFailed = it.address == null) }
            null
        }

    private suspend fun show(pass: DepositPass) {
        val read = pass.terms
        if (read is DepositTermsRead.Unavailable || read is DepositTermsRead.OtherDeployment) {
            mutableState.value = null
            return
        }
        val terms = (read as? DepositTermsRead.Pinned)?.terms?.also { lastTerms = it } ?: lastTerms
        val scan = pass.scan
        val indices =
            scan
                ?.used
                .orEmpty()
                .map { it.index }
                .toSet() + listOfNotNull(scan?.showing)
        val addresses = indices.associateWith { depositor.address(it) }
        mutableState.value =
            PrivateUsdDeposits(
                token = token,
                address = scan?.let { addresses[it.showing] },
                isAddressFailed = scan == null,
                terms = terms?.let(::depositTerms),
                isTermsFailed = terms == null,
                lines =
                    depositLines(pass, terms?.minValue, clock.now().epochSeconds, deployment.screeningTime) {
                        addresses.getValue(it)
                    },
            )
    }

    // Briefly, while the configuration loads as Zapp starts: until then, nothing new is signed.
    private suspend fun isShieldedUsdOn(): Boolean =
        withTimeoutOrNull(CONFIGURATION_WAIT) { configurationRepository.configurationFlow.filterNotNull().first() }
            ?.let { ConfigurationEntries.IS_PRIVATE_USD_AVAILABLE.getValue(it) } == true

    private companion object {
        val CONFIGURATION_WAIT = 5.seconds
    }
}

// None between a reset and the wallet that replaces it: the seed is gone.
private suspend fun RailgunMnemonicProvider.hasWallet(): Boolean =
    runCatching { address() }
        .onFailure { if (it is CancellationException) throw it }
        .isSuccess

/**
 * This build's deposits: its deployment's relayer, over [http], which never retries, and its chain, or none where the
 * deployment pins no Deposits contract. Notes are built from the seed in libzecswap ([PrivateUsdDepositNotes]).
 */
internal fun privateUsdDeposits(
    deployments: AtomicSwapDeployments,
    http: HttpClient,
    keys: PrivateUsdDepositKeyring,
    notes: PrivateUsdDepositNotes,
    store: PrivateUsdDepositStore,
    railgunWalletRepository: RailgunWalletRepository,
    balanceRepository: Lazy<PrivateUsdBalanceRepository>,
    configurationRepository: ConfigurationRepository,
    railgunMnemonicProvider: RailgunMnemonicProvider,
): PrivateUsdDepositsRepository {
    val deployment = deployments.current
    val pin = deployment?.usdcDeposits ?: return NoPrivateUsdDeposits
    val depositor =
        Depositor(
            pin = pin,
            relayer = DepositsClient(http, deployment.swap.relayerUrl),
            chain = DepositsChain(BaseRpcClient(RpcHttpClient.create(), deployment.swap.rpcEndpoint), pin),
            keys = keys,
            notes = notes,
            store = store,
            now = { Clock.System.now().epochSeconds },
            // Indices and reasons only: never an address, a note or a signature.
            log = { Twig.warn { "Private USD: deposits: $it" } },
        )
    return PrivateUsdDepositsRepositoryImpl(
        deployment = deployment,
        depositor = depositor,
        store = store,
        railgunWalletRepository = railgunWalletRepository,
        balanceRepository = balanceRepository,
        configurationRepository = configurationRepository,
        railgunMnemonicProvider = railgunMnemonicProvider,
        scope = backgroundScope("Private USD deposits"),
        clock = Clock.System,
    )
}
