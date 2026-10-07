// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.offramp.atomicswap.AtomicSwapChain
import xyz.justzappit.offramp.atomicswap.AtomicSwapChainReader
import xyz.justzappit.offramp.atomicswap.AtomicSwapDriver
import xyz.justzappit.offramp.atomicswap.AtomicSwapKeys
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.AtomicSwapStore
import xyz.justzappit.offramp.atomicswap.AtomicSwapZcash
import xyz.justzappit.offramp.atomicswap.MakerClient
import xyz.justzappit.offramp.atomicswap.RelayerClient
import xyz.justzappit.offramp.atomicswap.ReverseSwapDriver
import xyz.justzappit.offramp.atomicswap.ReverseSwapFunding
import xyz.justzappit.offramp.atomicswap.ReverseSwapKeys
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.ReverseSwapStore
import xyz.justzappit.offramp.atomicswap.ReverseSwapZcash
import xyz.justzappit.offramp.atomicswap.SwapContract
import xyz.justzappit.offramp.atomicswap.SwapDeployment
import xyz.justzappit.offramp.atomicswap.SwapIndices
import xyz.justzappit.offramp.atomicswap.ZcashDepositTerms

/** One wallet's drivers on each deployment; a reset closes its session, and nothing its drivers keep after lands. */
class AtomicSwapSessions(
    private val http: HttpClient,
    private val keys: AtomicSwapKeys,
    private val zcash: AtomicSwapZcash,
    private val store: AtomicSwapStore,
    private val reverseKeys: ReverseSwapKeys,
    private val reverseZcash: ReverseSwapZcash,
    private val reverseStore: ReverseSwapStore,
    private val wallet: RailgunWalletRepository,
    private val tokens: AtomicSwapTokens,
) {
    // Guarded by `this`; none while a reset wipes the wallet's records.
    private var current: Session? = Session(number = 0)
    private var opened = 0L

    /** Held while a conversion is accepted either way, so only one goes ahead at a time. */
    val acceptanceLock = Mutex()

    /** The session work begins in now, to hand [forward] and [reverse] to stay in; [CLOSED] during a reset. */
    val session: Long get() = synchronized(this) { current?.number ?: CLOSED }

    /** Indices for swaps either way, checked against every contract this wallet's swaps have used. */
    val indices: SwapIndices get() = open().indices

    fun forward(
        deployment: AtomicSwapDeployment,
        session: Long = this.session,
    ): AtomicSwapDriver = open(session).deployment(deployment.swap).forward(deployment.deposits)

    /** The driver of [record], on the deployment it was accepted on, as long as [session] lasts. */
    fun forward(
        record: AtomicSwapRecord,
        session: Long = this.session,
    ): AtomicSwapDriver = deploymentFor(record).let { open(session).deployment(it.swap).forward(it.deposits) }

    fun chain(record: AtomicSwapRecord): AtomicSwapChainReader = open().deployment(deploymentFor(record).swap).chain

    /** The driver of reverse swaps on [deployment], which their records keep, as long as [session] lasts. */
    fun reverse(
        deployment: SwapDeployment,
        session: Long = this.session,
    ): ReverseSwapDriver = open(session).deployment(deployment).reverse

    /** How reverse swaps on [deployment] pay their escrow from the private balance. */
    fun reverseFunding(deployment: SwapDeployment): ReverseSwapFunding = open().deployment(deployment).funding

    /** Closes the session once what its drivers write lands, runs [wipe], then opens the next. */
    suspend fun reset(wipe: suspend () -> Unit) {
        val closed = synchronized(this) { current.also { current = null } }
        try {
            closed?.close()
            wipe()
        } finally {
            synchronized(this) { current = Session(number = ++opened) }
        }
    }

    private fun open(session: Long = this.session): Session =
        synchronized(this) { checkNotNull(current?.takeIf { it.number == session }) { "the wallet was reset" } }

    private inner class Session(
        val number: Long
    ) {
        private val deployments = mutableMapOf<SwapDeployment, Deployment>()
        val writes = SessionWrites(store, reverseStore)

        val indices: SwapIndices by lazy {
            SwapIndices(writes.forward, keys, KNOWN.map { SwapContract(deployment(it.swap).chain, it.swap.maker) })
        }

        fun deployment(swap: SwapDeployment): Deployment =
            synchronized(deployments) { deployments.getOrPut(swap) { Deployment(swap, this) } }

        suspend fun close() = writes.close()
    }

    private inner class Deployment(
        private val deployment: SwapDeployment,
        private val session: Session,
    ) {
        private val rpc = BaseRpcClient(RpcHttpClient.create(), deployment.rpcUrl.toString())
        private val maker =
            deployment.tokenIssuer?.let { MakerClient(http, deployment.makerUrl, tokens.source(it)) }
                ?: MakerClient(http, deployment.makerUrl)
        private val relayer = RelayerClient(http, deployment.relayerUrl)
        private val forwardDrivers = mutableMapOf<ZcashDepositTerms, AtomicSwapDriver>()
        val chain = AtomicSwapChain(rpc, deployment)
        val funding = ReverseSwapFundingImpl(wallet, rpc, deployment, relayer)

        val reverse: ReverseSwapDriver by lazy {
            ReverseSwapDriver(
                deployment = deployment,
                maker = maker,
                relayer = relayer,
                chain = chain,
                keys = keys,
                reverseKeys = reverseKeys,
                zcash = reverseZcash,
                funding = funding,
                indices = session.indices,
                forward = session.writes.forward,
                store = session.writes.reverse,
            )
        }

        fun forward(terms: ZcashDepositTerms): AtomicSwapDriver =
            synchronized(forwardDrivers) {
                forwardDrivers.getOrPut(terms) {
                    AtomicSwapDriver(
                        deployment = deployment,
                        depositTerms = terms,
                        maker = maker,
                        relayer = relayer,
                        chain = chain,
                        keys = keys,
                        zcash = zcash,
                        store = session.writes.forward,
                        indices = session.indices,
                    )
                }
            }
    }

    companion object {
        /** The session of no wallet: work begun in it runs nowhere. */
        const val CLOSED = -1L

        // Forward swaps keep the deployment they were accepted on, found again from their quote. Indices are checked
        // against these alone: a contract users swapped on stays, or a restored wallet reuses secrets published there.
        private val KNOWN = listOf(AtomicSwapTestnet.deployment)

        fun deploymentFor(record: AtomicSwapRecord): AtomicSwapDeployment =
            checkNotNull(
                KNOWN.firstOrNull {
                    it.swap.chainId == record.quote.chainId &&
                        it.swap.contract == record.quote.contract &&
                        it.swap.token == record.quote.token
                },
            ) { "unknown original swap deployment" }
    }
}

/** One session's writes to the swap stores, which closing lets land and refuses from then on. */
private class SessionWrites(
    forward: AtomicSwapStore,
    reverse: ReverseSwapStore,
) {
    private val lock = Mutex()
    private var isClosed = false

    val forward =
        object : AtomicSwapStore {
            override suspend fun takeIndex() = write { forward.takeIndex() }

            override suspend fun active() = forward.active()

            override suspend fun save(record: AtomicSwapRecord) = write { forward.save(record) }
        }

    val reverse =
        object : ReverseSwapStore {
            override suspend fun active() = reverse.active()

            override suspend fun find(index: Int) = reverse.find(index)

            override suspend fun save(record: ReverseSwapRecord) = write { reverse.save(record) }

            override suspend fun update(record: ReverseSwapRecord) = write { reverse.update(record) }
        }

    suspend fun close() = lock.withLock { isClosed = true }

    private suspend fun <T> write(write: suspend () -> T): T =
        lock.withLock {
            check(!isClosed) { "the wallet was reset" }
            write()
        }
}
