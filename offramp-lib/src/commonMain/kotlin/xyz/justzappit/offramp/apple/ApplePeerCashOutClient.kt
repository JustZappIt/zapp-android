// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.math.decimalToPlainString
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.funding.NoRouteOfframpTopUp
import xyz.justzappit.offramp.orchestrator.platformCurrentTimeMillis
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getUsdcBalance
import xyz.justzappit.offramp.peer.AaPeerCashOutDriver
import xyz.justzappit.offramp.peer.PeerCashOutCheckpoint
import xyz.justzappit.offramp.peer.PeerCashOutId
import xyz.justzappit.offramp.peer.PeerCashOutOrchestrator
import xyz.justzappit.offramp.peer.PeerCashOutRequest
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerCashOutStep
import xyz.justzappit.offramp.peer.PeerConfigProvider
import xyz.justzappit.offramp.peer.PeerCuratorClient
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerDepositId
import xyz.justzappit.offramp.peer.PeerErrorCode
import xyz.justzappit.offramp.peer.PeerException
import xyz.justzappit.offramp.peer.PeerIndexerClient
import xyz.justzappit.offramp.peer.PeerMarket
import xyz.justzappit.offramp.peer.PeerMarketSnapshot
import xyz.justzappit.offramp.peer.PeerNetworkConfig
import xyz.justzappit.offramp.peer.PeerNetworks
import xyz.justzappit.offramp.peer.PeerOracleRate
import xyz.justzappit.offramp.peer.PeerOrderSnapshot
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.PeerResumeAction
import xyz.justzappit.offramp.peer.asError
import xyz.justzappit.offramp.peer.depositId
import xyz.justzappit.offramp.peer.step

/**
 * Swift-friendly facade over the Peer maker rails. Swift never touches the protocol types: their
 * inline value classes, `BigInteger` amounts and throwing flows do not cross the Objective-C bridge
 * in a usable form, and a boundary that leaks them breaks on every Kotlin refactor.
 *
 * What lives here rather than in Swift, and why:
 *  - checkpoint serialization and the persist-before-broadcast ordering, because getting that order
 *    wrong escrows the user's USDC twice and there must be exactly one implementation of it;
 *  - the payee book, because pairing a curator hash with the exact handle it was registered for is
 *    what stops a reused hash funding a deposit that pays somebody else;
 *  - reconciliation, because only the protocol layer can resolve a persisted UserOperation
 *    identity to the deposit receipt it created.
 *
 * What stays in Swift: task ownership, reservations for attempts too young to have a checkpoint,
 * local authentication, navigation, and every user-visible string.
 */
@Suppress("TooManyFunctions") // The facade mirrors the complete maker surface for Swift.
class ApplePeerCashOutClient private constructor(
    private val rail: Rail?,
    private val network: P2pNetworkConfig,
    private val smartAccounts: SmartOfframpAccountProvider,
    private val rpc: BaseRpcClient,
    private val checkpoints: ApplePeerCheckpointBook,
    private val payees: ApplePeerPayeeBook,
) {
    private val marketCache = mutableMapOf<String, CachedMarket>()
    private val marketMutex = Mutex()

    /**
     * The rails, their currencies and the two floors. Swift gates every Peer surface on
     * [ApplePeerCapabilities.isAvailable]: Peer exists only on Base mainnet, and an absent rail is
     * the honest answer rather than a half-working one.
     */
    fun capabilities(): ApplePeerCapabilities =
        ApplePeerCapabilities(
            isAvailable = rail != null,
            networkName = rail?.network?.name,
            platforms = PeerPlatform.entries.map { it.toApple() },
            minimumMicros = PeerNetworks.MIN_CASHOUT_MICROS.toString(),
            recommendedMinimumMicros = PeerNetworks.RECOMMENDED_MIN_CASHOUT_MICROS.toString(),
            attemptIdByteCount = PeerCashOutId.SIZE_BYTES,
        )

    /**
     * Pure, so the amount screen can echo what a handle registers as on every keystroke without a
     * round trip. A null [ApplePeerHandleCheck.normalized] is a format rejection only — whether the
     * account exists is the curator's answer, and it is given inside the cash-out flow.
     */
    fun normalizeHandle(platformCode: String, raw: String): ApplePeerHandleCheck {
        val platform = platformOf(platformCode)
        val normalized =
            runCatching { platform.normalizeHandle(raw) }
                .getOrNull()
                ?.takeIf(platform::hasPlausibleFormat)
        return ApplePeerHandleCheck(
            normalized = normalized?.value,
            changedWhatWasTyped = normalized != null && normalized.value != raw.trim(),
            validatesLive = platform.validatesHandleLive,
        )
    }

    /** The handle last used on this rail, so the form is pre-filled rather than retyped. */
    @Throws(Exception::class)
    suspend fun storedHandle(platformCode: String): String? = payees.get(platformOf(platformCode))?.handle?.value

    /**
     * A failed balance read is a null balance, never zero: the amount screen has to refuse an order
     * it cannot check rather than report the account as empty.
     */
    @Throws(Exception::class)
    suspend fun account(): ApplePeerAccount {
        val address = smartAccounts.resolve().address
        return ApplePeerAccount(
            address = address.checksumHex,
            balanceMicros =
                runCatching { rpc.getUsdcBalance(network.usdcAddress, address) }.getOrNull()?.micros?.toString(),
            explorerUrl = network.addressUrl(address.checksumHex),
        )
    }

    /**
     * Indicative, and re-read on every entry rather than cached: the binding rate is whatever the
     * oracle says when a buyer signals. Null when the feed is unreadable or stale, because a rate
     * shown as live has to be one.
     */
    @Throws(Exception::class)
    suspend fun rate(currencyCode: String): ApplePeerRate? {
        val peer = rail ?: return null
        return peer.oracle.quote(currencyOf(currencyCode), nowSeconds())?.let {
            ApplePeerRate(
                currencyCode = it.currency.code,
                fiatPerUsdc = decimalToPlainString(it.fiatPerUsdc),
                readAtEpochSeconds = it.readAtSeconds,
            )
        }
    }

    /**
     * Whether and when this pair actually fills, measured from the free indexer before the user
     * commits anything. Fails open to null: a read failure means generic copy, never an invented
     * estimate. Cached because the numbers move slowly and the amount screen is re-entered often.
     */
    @Throws(Exception::class)
    suspend fun market(platformCode: String, currencyCode: String, amountMicros: String?): ApplePeerMarket? {
        val peer = rail ?: return null
        val platform = platformOf(platformCode)
        val currency = currencyOf(currencyCode)
        // Serialised, because the cache is one map shared by every caller: two entering at once
        // would both write it and both pay for the same pair of indexer reads.
        val snapshot =
            marketMutex.withLock {
                val now = nowSeconds()
                val key = platform.wireName + CACHE_KEY_SEPARATOR + currency.code
                marketCache[key]
                    ?.takeIf { now - it.atSeconds < PeerMarket.CACHE_TTL_SECONDS }
                    ?.snapshot
                    ?: readMarket(peer, platform, currency, now)?.also { marketCache[key] = CachedMarket(it, now) }
            }
        return snapshot?.toApple(amountMicros?.let(::usdcOrNull))
    }

    /**
     * Every attempt with a durable record, whether or not this process is driving it. Swift
     * subtracts the ones still holding unescrowed funds from the Base balance: an amount is not gone
     * from the account until `createDeposit` is mined, so the raw balance says it is still there and
     * three consecutive orders would each spend the same coins.
     */
    @Throws(Exception::class)
    suspend fun attempts(): List<ApplePeerAttempt> = checkpoints.all().map { it.toApple() }

    /**
     * Teaches stored attempts that their deposit already exists. Without it the amount an attempt
     * was written for is subtracted from the balance twice — once by the escrow that holds it, once
     * by the reservation — and only reopening that attempt by hand would ever recover it.
     *
     * [drivingAttemptIds] are the attempts the caller is running right now: those own their own
     * checkpoints and are left alone, because a resume started inside this read writes transaction
     * hashes that stamping would overwrite with a copy taken before they existed.
     */
    @Throws(Exception::class)
    suspend fun reconcile(drivingAttemptIds: List<String>): List<ApplePeerReconciliation> {
        val peer = rail ?: return emptyList()
        val driving = drivingAttemptIds.mapNotNull(PeerCashOutId::ofOrNull).toSet()
        val stored = checkpoints.all()
        val unresolved = stored.filterNot { it.id in driving }.filter(::isReconcilable)
        return unresolved.mapNotNull { checkpoint ->
            try {
                stamp(checkpoint.id, peer.orchestrator.resolveCheckpoint(checkpoint))
            } catch (error: CancellationException) {
                throw error
            } catch (error: PeerException) {
                if (!error.error.nothingEscrowed) return@mapNotNull null
                checkpoints.clear(checkpoint.id)
                ApplePeerReconciliation(
                    attemptId = checkpoint.id.value,
                    retiredWithoutEscrow = true,
                )
            } catch (
                @Suppress("SwallowedException", "TooGenericExceptionCaught") error: Exception,
            ) {
                null
            }
        }
    }

    /**
     * Open orders for this seed, read from the chain. Filtered on the phase rather than the
     * indexer's ACTIVE flag: withdrawing calls `removeFunds`, which empties a deposit without
     * closing it, so a drained order stays ACTIVE forever and would sit in the list reading
     * "0 USDC on offer, waiting for a buyer".
     */
    @Throws(Exception::class)
    suspend fun activeOrders(): List<ApplePeerOrder> =
        (rail ?: return emptyList())
            .orchestrator
            .activeOrders()
            .filterNot { it.phase.isFinished }
            .map(::orderOf)

    /** Closed orders included, newest first. A history needs the ones the active list drops. */
    @Throws(Exception::class)
    suspend fun allOrders(): List<ApplePeerOrder> =
        (rail ?: return emptyList())
            .orchestrator
            .allOrders()
            .sortedByDescending { it.creationBlockNumber ?: 0L }
            .map(::orderOf)

    /** A missing order is null. Read failures propagate so an outage cannot look like deletion. */
    @Throws(Exception::class)
    suspend fun order(depositIdComposite: String): ApplePeerOrder? {
        val peer = rail ?: return null
        return peer.indexer.order(depositIdOf(depositIdComposite))?.let(::orderOf)
    }

    fun transactionUrl(txHash: String): String = network.txUrl(txHash)

    /**
     * Runs a cash-out. Idempotent against its own history: an attempt that already has a checkpoint
     * resolves whatever was broadcast instead of sending it again, so a retry from the progress
     * screen can never open a second escrow. That decision is made here rather than in Swift because
     * getting it wrong costs the user the whole order amount.
     */
    fun run(request: ApplePeerCashOutRequest): Flow<ApplePeerStatus> =
        cashOutFlow(request.attemptId) {
            val id = cashOutIdOf(request.attemptId)
            val checkpoint = checkpoints.get(id)
            drive(id, checkpoint?.let(::requestFrom) ?: freshRequestOf(request), checkpoint)
        }

    /**
     * Picks an attempt back up from its checkpoint alone, which is all a process that has died and
     * restarted has. The handle is PII and is never persisted; the registered hash the checkpoint
     * carries is the only part of a payee the protocol needs, so this is enough.
     */
    fun resume(attemptId: String): Flow<ApplePeerStatus> =
        cashOutFlow(attemptId) {
            val id = cashOutIdOf(attemptId)
            when (val checkpoint = checkpoints.get(id)) {
                null -> emit(failed(attemptId, PeerErrorCode.ORDER_NOT_FOUND, PeerCashOutStep.INITIALIZATION))
                else -> drive(id, requestFrom(checkpoint), checkpoint)
            }
        }

    /**
     * Watches a live order. Separate from [run], which stops as soon as the deposit exists: from
     * there the chain is the record and there is nothing left for the setup flow to drive.
     */
    fun observeOrder(depositIdComposite: String): Flow<ApplePeerStatus> =
        orderFlow(depositIdComposite, PeerCashOutStep.AWAITING_BUYER) { observeOrder(it) }

    fun withdraw(depositIdComposite: String, amountMicros: String): Flow<ApplePeerStatus> =
        orderFlow(depositIdComposite, PeerCashOutStep.WITHDRAWING) { withdraw(it, usdcOf(amountMicros)) }

    fun setAcceptingIntents(depositIdComposite: String, accepting: Boolean): Flow<ApplePeerStatus> =
        orderFlow(depositIdComposite, PeerCashOutStep.AWAITING_BUYER) { setAcceptingIntents(it, accepting) }

    /**
     * Stops at the live order rather than polling it forever. From there the order screen observes
     * the chain, and a second endless poll here would hold the attempt open for the life of the app.
     */
    private suspend fun FlowCollector<ApplePeerStatus>.drive(
        id: PeerCashOutId,
        request: PeerCashOutRequest,
        checkpoint: PeerCashOutCheckpoint?,
    ) {
        val orchestrator = requireRail().orchestrator
        val persister = ApplePeerCheckpointPersister(checkpoints, id, request)
        persister.seedFrom(checkpoint)
        rememberTypedHandle(request)
        val source = if (checkpoint != null) orchestrator.resume(checkpoint) else orchestrator.createOrder(request)
        try {
            source
                .transformWhile { status ->
                    emit(status)
                    status !is PeerCashOutStatus.OrderLive
                }.collect { status ->
                    persister.onStatus(status)
                    rememberRegisteredHandle(status, request)
                    emit(status.asApple(id.value))
                }
        } catch (error: PeerException) {
            if (error.error.code == PeerErrorCode.INITIALIZATION_FAILED && error.hasRecoveryStorageCause()) {
                try {
                    // The host may atomically replace the JSON and then fail while applying file
                    // attributes. The submitter still did not send because its callback threw, so
                    // make a second best-effort atomic replacement that retires that stale marker.
                    checkpoints.clear(id)
                } catch (
                    @Suppress("SwallowedException", "TooGenericExceptionCaught") cleanupError: Exception,
                ) {
                    // If the same post-write attribute step failed again, the clear still landed.
                    // If the underlying write failed, the original marker write did not land either.
                }
            }
            throw error
        }
    }

    private suspend fun freshRequestOf(request: ApplePeerCashOutRequest): PeerCashOutRequest {
        val platform = platformOf(request.platformCode)
        val handle = platform.normalizeHandle(request.handle)
        val stored = payees.get(platform)
        return PeerCashOutRequest(
            platform = platform,
            handle = handle,
            currencies = request.currencyCodes.map(::currencyOf),
            amount = usdcOf(request.amountMicros),
            // Registration is per handle, so a hash already registered for this exact one skips the
            // curator round trip. A hash carried across an edit would fund a deposit paying the
            // handle it was registered for, not the one on screen.
            cachedPayeeHash = stored?.hash?.takeIf { stored.handle == handle },
        )
    }

    /**
     * A resumed attempt is described entirely by its own record. Rebuilding from the caller's
     * request instead would let a retry pay whatever handle the rail's field happens to hold now,
     * which is not necessarily the payee this attempt escrowed against.
     */
    private fun requestFrom(checkpoint: PeerCashOutCheckpoint): PeerCashOutRequest =
        PeerCashOutRequest(
            platform = checkpoint.platform,
            handle = null,
            currencies = checkpoint.currencies,
            amount = checkpoint.amount,
            cachedPayeeHash = checkpoint.payeeHash,
        )

    /**
     * Records what the user typed before anything is sent, so the rail's field is pre-filled next
     * time even if this attempt fails. A resumed attempt has no handle to record — only its hash —
     * which is why this is a no-op there rather than clearing what is stored.
     */
    private suspend fun rememberTypedHandle(request: PeerCashOutRequest) {
        val handle = request.handle ?: return
        payees.store(platform = request.platform, handle = handle, hash = request.cachedPayeeHash)
    }

    private suspend fun rememberRegisteredHandle(status: PeerCashOutStatus, request: PeerCashOutRequest) {
        val hash = (status as? PeerCashOutStatus.ValidatingPayee)?.payeeHash ?: return
        val handle = request.handle ?: return
        payees.store(platform = request.platform, handle = handle, hash = hash)
    }

    private fun orderFlow(
        depositIdComposite: String,
        step: PeerCashOutStep,
        operation: PeerCashOutOrchestrator.(PeerDepositId) -> Flow<PeerCashOutStatus>,
    ): Flow<ApplePeerStatus> =
        statusFlow(depositIdComposite, step) {
            requireRail()
                .orchestrator
                .operation(depositIdOf(depositIdComposite))
                .collect { emit(it.asApple(depositIdComposite)) }
        }

    private fun cashOutFlow(
        attemptId: String,
        block: suspend FlowCollector<ApplePeerStatus>.() -> Unit,
    ): Flow<ApplePeerStatus> = statusFlow(attemptId, PeerCashOutStep.INITIALIZATION, block)

    /**
     * Swift's Kotlin flow bridge cannot carry an exception, so a flow that fails outside the
     * orchestrator's own handling would reach the reducer as an ordinary end of stream — which reads
     * as "finished" on an operation that never ran. Every entry point reports the failure as a
     * status instead.
     *
     * Recovery storage has its own typed boundary because only that failure makes the previous
     * outcome unknowable. Malformed input and setup failures are proven pre-send and must not retain
     * an untouched balance indefinitely.
     */
    private fun statusFlow(
        subjectId: String,
        step: PeerCashOutStep,
        block: suspend FlowCollector<ApplePeerStatus>.() -> Unit,
    ): Flow<ApplePeerStatus> =
        flow {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (
                @Suppress("SwallowedException", "TooGenericExceptionCaught")
                error: Exception,
            ) {
                emit(failed(subjectId, applePeerFacadeErrorCode(rail != null, error), step))
            }
        }

    private fun failed(subjectId: String, code: PeerErrorCode, step: PeerCashOutStep): ApplePeerStatus =
        PeerCashOutStatus.Failed(step = step, error = code.asError()).asApple(subjectId)

    // Re-read under the conditions the decision was made on: the order read is a network round trip,
    // and a resume started inside it has written hashes a stale copy would erase.
    private suspend fun stamp(id: PeerCashOutId, depositId: PeerDepositId): ApplePeerReconciliation? =
        checkpoints
            .get(id)
            ?.takeIf(::isReconcilable)
            ?.let {
                checkpoints.store(it.copy(depositId = depositId))
                ApplePeerReconciliation(
                    attemptId = id.value,
                    depositIdComposite = depositId.composite,
                )
            }

    /**
     * Only an attempt with an exact signed submission identity can be reconciled. Earlier states
     * remain committed but must never be matched to an order by mutable business fields.
     */
    private fun isReconcilable(checkpoint: PeerCashOutCheckpoint): Boolean =
        checkpoint.holdsUnescrowedFunds &&
            when (checkpoint.resumeAction) {
                is PeerResumeAction.ResolveSubmittedDeposit,
                is PeerResumeAction.ReconcileSubmission,
                -> true

                else -> false
            }

    private suspend fun readMarket(
        peer: Rail,
        platform: PeerPlatform,
        currency: PeerCurrency,
        nowSeconds: Long,
    ): PeerMarketSnapshot? {
        val queue =
            runCatching {
                peer.indexer.queueSamples(
                    platform = platform,
                    currency = currency,
                    maturedBeforeSeconds = nowSeconds - PeerMarket.MATURITY_SECONDS,
                )
            }.getOrNull()
        val fills =
            runCatching {
                peer.indexer.fillSamples(platform, nowSeconds - PeerMarket.WINDOW_SECONDS)
            }.getOrNull()
        // Both halves or nothing: a band computed from one of them describes a market nobody saw.
        if (queue == null || fills == null) return null
        return PeerMarket.summarise(
            platform = platform,
            currency = currency,
            queueSamples = queue,
            fillSamples = fills,
            nowSeconds = nowSeconds,
        )
    }

    private fun orderOf(snapshot: PeerOrderSnapshot): ApplePeerOrder = snapshot.toApple(rail?.network)

    private fun PeerCashOutStatus.asApple(subjectId: String): ApplePeerStatus = toApple(subjectId, rail?.network)

    private fun requireRail(): Rail = checkNotNull(rail) { "Peer cash-out is only available on Base mainnet." }

    private fun platformOf(code: String): PeerPlatform =
        requireNotNull(PeerPlatform.fromWireNameOrNull(code)) { "Unknown Peer platform '$code'" }

    private fun currencyOf(code: String): PeerCurrency =
        requireNotNull(PeerCurrency.fromCodeOrNull(code)) { "Unknown Peer currency '$code'" }

    private fun depositIdOf(composite: String): PeerDepositId =
        requireNotNull(PeerDepositId.parseOrNull(composite)) { "Malformed Peer deposit id" }

    private fun cashOutIdOf(value: String): PeerCashOutId =
        requireNotNull(PeerCashOutId.ofOrNull(value)) { "Malformed Peer cash-out id" }

    /** One Peer deployment and the three clients scoped to it. Absent means the rails are absent. */
    internal class Rail(
        val network: PeerNetworkConfig,
        val orchestrator: PeerCashOutOrchestrator,
        val indexer: PeerIndexerClient,
        val oracle: PeerOracleRate,
    )

    private class CachedMarket(
        val snapshot: PeerMarketSnapshot,
        val atSeconds: Long,
    )

    companion object {
        /**
         * Peer never funds the account: a cash-out spends Base USDC the user already has, and
         * topping up is its own screen with its own progress and its own authentication. The
         * orchestrator is therefore handed a top-up that refuses — the only path that could reach it
         * is resuming a bridge a previous build started, and iOS has never had a Peer rail to start
         * one.
         */
        @Throws(Exception::class)
        suspend fun create(
            account: AppleBaseAccount,
            storage: ApplePeerCashOutStorage,
        ): ApplePeerCashOutClient {
            val checkpointBook = ApplePeerCheckpointBook(storage)
            val peerNetwork = PeerConfigProvider(account.network.name).currentOrNull()
            if (peerNetwork != null) {
                val pending = checkpointBook.all().mapNotNull { it.pendingSubmissionForNonceRestore() }
                if (pending.isNotEmpty()) {
                    val submitter = account.submitters.resolve().submitter
                    pending.forEach { submission ->
                        submitter.restorePendingTransaction(submission.hash, submission.nonce)
                    }
                }
            }
            return ApplePeerCashOutClient(
                rail = peerNetwork?.let { railFor(account, it) },
                network = account.network,
                smartAccounts = account.smartAccounts,
                rpc = account.rpc,
                checkpoints = checkpointBook,
                payees = ApplePeerPayeeBook(storage),
            )
        }

        private fun railFor(account: AppleBaseAccount, network: PeerNetworkConfig): Rail {
            val indexer = PeerIndexerClient(account.httpClient, network.indexerUrl)
            return Rail(
                network = network,
                orchestrator =
                    AaPeerCashOutDriver(
                        rpc = account.rpc,
                        peerNetwork = network,
                        submitters = account.submitters,
                        curatorClient = PeerCuratorClient(account.httpClient, network.curatorUrl),
                        indexerClient = indexer,
                        topUp = NoRouteOfframpTopUp(),
                    ),
                indexer = indexer,
                oracle = PeerOracleRate(account.rpc),
            )
        }

        private const val CACHE_KEY_SEPARATOR = ":"
        private const val MILLIS_PER_SECOND = 1_000L

        private fun nowSeconds(): Long = platformCurrentTimeMillis() / MILLIS_PER_SECOND

        private fun usdcOf(micros: String): Usdc6 = usdcFromMicros(micros)

        private fun usdcOrNull(micros: String): Usdc6? = runCatching { usdcFromMicros(micros) }.getOrNull()
    }
}

private data class PendingApplePeerSubmission(
    val hash: xyz.justzappit.evm.types.TxHash?,
    val nonce: xyz.justzappit.evm.math.BigInteger?,
)

private fun PeerCashOutCheckpoint.pendingSubmissionForNonceRestore(): PendingApplePeerSubmission? =
    when (val action = resumeAction) {
        is PeerResumeAction.ResolveSubmittedDeposit -> {
            PendingApplePeerSubmission(action.txHash, action.submissionNonce)
        }

        is PeerResumeAction.ReconcileSubmission -> {
            PendingApplePeerSubmission(action.submissionHash, action.submissionNonce)
        }

        else -> {
            null
        }
    }

internal fun applePeerFacadeErrorCode(railAvailable: Boolean, error: Exception): PeerErrorCode =
    when {
        !railAvailable -> PeerErrorCode.UNSUPPORTED_PLATFORM

        // An explicit protocol classification carries transaction context that a nested cause does
        // not. In particular, a checkpoint-marker write can fail before the submitter sends; its
        // storage cause must not convert that proven pre-send outcome into an indefinite reservation.
        error is PeerException -> error.error.code

        error.hasRecoveryStorageCause() -> PeerErrorCode.RECOVERY_STATE_UNREADABLE

        error is IllegalArgumentException -> PeerErrorCode.INVALID_REQUEST

        else -> PeerErrorCode.INITIALIZATION_FAILED
    }

private fun Throwable.hasRecoveryStorageCause(): Boolean {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        if (current is ApplePeerRecoveryStorageException) return true
        current = current?.cause
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8
