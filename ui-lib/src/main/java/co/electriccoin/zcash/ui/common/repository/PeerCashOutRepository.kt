package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.PeerCashOutCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.provider.PeerPayeeHandleProvider
import co.electriccoin.zcash.ui.screen.swap.peer.progress.PeerCashOutCheckpointPersister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PeerCashOutCheckpoint
import xyz.justzappit.offramp.peer.PeerCashOutId
import xyz.justzappit.offramp.peer.PeerCashOutOrchestrator
import xyz.justzappit.offramp.peer.PeerCashOutRequest
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerDepositId
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.depositId
import java.security.SecureRandom

/**
 * Owns the coroutine that drives a cash-out from Continue to a live order, on an application-lifetime
 * scope rather than a ViewModel's: "the screen is visible" and "the transfer is running" are not the
 * same fact. A withdrawal is the same — the UserOperation is with the bundler by the time the screen
 * goes away — so [orderActions] runs those here too and both surfaces read the one state.
 *
 * Several attempts run at once. Each is keyed by its own [PeerCashOutId], and the status history is
 * kept so a subscriber arriving after a rotation sees the prefix rather than only the latest step.
 */
interface PeerCashOutRepository {
    val runs: StateFlow<List<PeerCashOutRun>>

    /** Withdrawals and matching toggles in flight, by the order they act on. */
    val orderActions: StateFlow<Map<PeerDepositId, PeerOrderActionRun>>

    fun newId(): PeerCashOutId

    fun start(id: PeerCashOutId, request: PeerCashOutRequest)

    /**
     * Picks an attempt back up, from its checkpoint when it has one. A no-op while that attempt is
     * already running, which is what makes it safe to call on every entry to the progress screen.
     */
    fun resume(id: PeerCashOutId)

    fun observe(id: PeerCashOutId): Flow<PeerCashOutRun?>

    fun withdraw(depositId: PeerDepositId, amount: Usdc6)

    fun setAcceptingIntents(depositId: PeerDepositId, accepting: Boolean)

    /** Drops a finished action's record, so its error stops outliving the state it described. */
    fun clearOrderAction(depositId: PeerDepositId)

    /**
     * Records the deposit a reconciliation matched to an attempt this process is no longer driving.
     * Without it a settled attempt keeps reserving an amount the escrow already holds, and the
     * balance is short by it twice over.
     */
    fun onDepositReconciled(id: PeerCashOutId, depositId: PeerDepositId)

    /** Suspends until nothing is still driving the previous wallet's smart account. */
    suspend fun reset()
}

/** One attempt as the app currently knows it. [statuses] is the whole history, oldest first. */
data class PeerCashOutRun(
    val id: PeerCashOutId,
    val platform: PeerPlatform,
    val amount: Usdc6,
    val currencies: List<PeerCurrency>,
    val statuses: List<PeerCashOutStatus>,
    val startedAtMillis: Long,
    /** Whether this process is driving the attempt right now, rather than merely remembering it. */
    val isDriving: Boolean = false,
    /**
     * The deposit a later reconciliation matched to this attempt. An attempt whose submission
     * outcome was never known carries no status naming one, so without this its amount stays
     * reserved alongside the order it turned out to open.
     */
    val reconciledDepositId: PeerDepositId? = null,
) {
    val latest: PeerCashOutStatus get() = statuses.lastOrNull() ?: PeerCashOutStatus.Idle

    val depositId: PeerDepositId?
        get() = statuses.asReversed().firstNotNullOfOrNull { it.depositId } ?: reconciledDepositId

    val failure: PeerCashOutStatus.Failed? get() = latest as? PeerCashOutStatus.Failed

    /** True until the order exists on chain, which is exactly while no indexer list can show it. */
    val isUnindexed: Boolean get() = depositId == null

    /**
     * Whether this attempt still has a claim on the smart account's USDC. A failure before the
     * deposit was ever broadcast released nothing; from [PeerCashOutStatus.CreatingDeposit] onward a
     * send may have landed, so the amount stays committed until it is reconciled — unless the
     * failure itself proves the escrow took nothing, which a reverted send does.
     */
    val holdsFunds: Boolean
        get() {
            if (depositId != null) return false
            val failed = failure ?: return true
            return statuses.any { it is PeerCashOutStatus.CreatingDeposit } && !failed.error.nothingEscrowed
        }
}

enum class PeerOrderActionKind {
    WITHDRAW,
    SET_ACCEPTING,
}

/**
 * A withdrawal or a matching toggle, and how far it has got. Outlives the screen that asked for it:
 * the operation is with the bundler either way, and a second tap on re-entry would send it twice.
 */
data class PeerOrderActionRun(
    val depositId: PeerDepositId,
    val kind: PeerOrderActionKind,
    val latest: PeerCashOutStatus,
    val isRunning: Boolean,
    /** When the action stopped changing the escrow. Null while it still is. */
    val settledAtMillis: Long? = null,
) {
    val failure: PeerCashOutStatus.Failed? get() = latest as? PeerCashOutStatus.Failed

    /**
     * Whether the screen must keep its actions disabled. A settled action has already moved the
     * escrow while the figures on screen still come from an earlier poll, so releasing the button
     * before a later read lands offers an action the visible numbers cannot back.
     */
    fun awaitsConfirmation(readAtMillis: Long?): Boolean =
        when {
            isRunning -> true
            settledAtMillis == null -> false
            else -> readAtMillis == null || readAtMillis < settledAtMillis
        }
}

@Suppress("TooManyFunctions")
internal class PeerCashOutRepositoryImpl(
    private val orchestrator: PeerCashOutOrchestrator,
    private val checkpointStorage: PeerCashOutCheckpointStorageProvider,
    private val payeeHandleProvider: PeerPayeeHandleProvider,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PeerCashOutRepository {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val random = SecureRandom()
    private val mutex = Mutex()
    private val jobs = mutableMapOf<PeerCashOutId, Job>()

    /**
     * What each attempt was actually asked to do, kept for the length of the process. A retry reads
     * its payee from here rather than from the per-platform record, which is the user's current
     * handle and not necessarily the one this attempt was opened for.
     */
    private val requests = mutableMapOf<PeerCashOutId, PeerCashOutRequest>()
    private val state = MutableStateFlow<Map<PeerCashOutId, PeerCashOutRun>>(emptyMap())
    private val actions = MutableStateFlow<Map<PeerDepositId, PeerOrderActionRun>>(emptyMap())
    private val actionJobs = mutableMapOf<PeerDepositId, Job>()

    /**
     * Bumped by [reset]. Every entry point reads it before it launches and again once it holds the
     * lock: the launch itself is not covered by the lock, so a caller parked on it would otherwise
     * wake after the wipe and start driving the deleted wallet's smart account.
     */
    private val generation = MutableStateFlow(0)

    override val runs: StateFlow<List<PeerCashOutRun>> =
        state
            .map { current -> current.values.sortedBy { it.startedAtMillis } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val orderActions: StateFlow<Map<PeerDepositId, PeerOrderActionRun>> = actions

    init {
        scope.launch { hydrate() }
    }

    override fun newId(): PeerCashOutId =
        PeerCashOutId.of(ByteArray(PeerCashOutId.SIZE_BYTES).also(random::nextBytes))

    override fun start(id: PeerCashOutId, request: PeerCashOutRequest) {
        val startedIn = generation.value
        // Claimed on the caller's thread rather than a dispatch later, the same way an order action
        // claims itself: the amount screen validates against a balance with committed attempts
        // already subtracted, and a reservation nobody can observe yet lets a second tap spend the
        // same coins.
        if (!claim(seedOf(id, request, checkpoint = null))) return
        scope.launch {
            mutex.withLock {
                if (generation.value != startedIn) {
                    forget(id)
                    return@withLock
                }
                if (jobs[id]?.isActive == true) return@withLock
                requests[id] = request
                jobs[id] = launchDrive(id, request, checkpoint = null)
            }
        }
    }

    /**
     * Checkpoints this process did not write. After process death the only record of an attempt
     * whose deposit is not indexed yet is its checkpoint, and a surface built from [runs] plus the
     * indexer shows nothing at all — while the amount stays subtracted from Available with no row to
     * explain it and no route back to the attempt.
     */
    private suspend fun hydrate() {
        val startedIn = generation.value
        val stored =
            runCatching { checkpointStorage.all() }
                .onFailure { Twig.warn(it) { "PeerCashOutRepository: checkpoint hydration failed" } }
                .getOrDefault(emptyList())
        mutex.withLock {
            if (generation.value != startedIn) return@withLock
            stored
                .filter { it.holdsUnescrowedFunds && state.value[it.id] == null }
                .forEach { checkpoint -> record(dormantOf(checkpoint)) }
        }
    }

    /**
     * The checkpoint wins whenever there is one, because it is the only record that knows what has
     * already been broadcast. A retry with no checkpoint is a genuinely fresh attempt: nothing was
     * sent, since the block read before `createDeposit` is persisted before the send returns, and a
     * send that provably reverted retires its own record.
     */
    override fun resume(id: PeerCashOutId) {
        val startedIn = generation.value
        scope.launch {
            mutex.withLock {
                if (generation.value != startedIn || jobs[id]?.isActive == true) return@withLock
                // An attempt that reached the chain is done with setup for good. Its order is the
                // record from here, and the progress screen resumes on every entry — a second pass
                // through `createOrder` would escrow a second lot of USDC.
                if (state.value[id]?.depositId != null) return@withLock
                val checkpoint = readCheckpoint(id)
                val request = resumeRequest(id, checkpoint)
                if (request == null) {
                    Twig.warn { "PeerCashOutRepository: nothing to resume for $id" }
                    return@withLock
                }
                requests[id] = request
                record((state.value[id] ?: seedOf(id, request, checkpoint)).copy(statuses = emptyList()))
                jobs[id] = launchDrive(id, request, checkpoint)
            }
        }
    }

    override fun observe(id: PeerCashOutId): Flow<PeerCashOutRun?> = state.map { it[id] }

    override fun withdraw(depositId: PeerDepositId, amount: Usdc6) {
        runOrderAction(depositId, PeerOrderActionKind.WITHDRAW) { orchestrator.withdraw(depositId, amount) }
    }

    override fun setAcceptingIntents(depositId: PeerDepositId, accepting: Boolean) {
        runOrderAction(depositId, PeerOrderActionKind.SET_ACCEPTING) {
            orchestrator.setAcceptingIntents(depositId, accepting)
        }
    }

    /**
     * Wallet-scoped state on an application-lifetime scope. A seed change must not leave the
     * previous wallet's attempts running against the new one's smart account, and joining rather
     * than merely cancelling is what stops an in-flight status writing a checkpoint into storage
     * the wipe has already cleared.
     */
    override suspend fun reset() {
        val running =
            mutex.withLock {
                val all = jobs.values + actionJobs.values
                all.forEach(Job::cancel)
                generation.update { it + 1 }
                jobs.clear()
                actionJobs.clear()
                requests.clear()
                state.update { emptyMap() }
                actions.update { emptyMap() }
                all.toList()
            }
        running.joinAll()
    }

    private fun runOrderAction(
        depositId: PeerDepositId,
        kind: PeerOrderActionKind,
        action: () -> Flow<PeerCashOutStatus>,
    ) {
        // Claimed on the caller's thread so the button is disabled on the same frame as the tap.
        if (!claimAction(depositId, kind)) return
        val startedIn = generation.value
        scope.launch {
            mutex.withLock {
                if (generation.value != startedIn) return@withLock
                // The claim is the mutual exclusion, so anything still here has already released it
                // and is inside its own `finally`. Waiting that out keeps the tap; reading it as a
                // live action instead drops the send while the button reports it sent.
                actionJobs[depositId]?.join()
                actionJobs[depositId] =
                    scope.launch {
                        try {
                            action().collect { status -> updateAction(depositId) { it.copy(latest = status) } }
                        } finally {
                            settleAction(depositId)
                        }
                    }
            }
        }
    }

    private fun updateAction(depositId: PeerDepositId, transform: (PeerOrderActionRun) -> PeerOrderActionRun) {
        actions.update { current ->
            val run = current[depositId] ?: return@update current
            current + (depositId to transform(run))
        }
    }

    /**
     * Only the caller's thread may start an action, so a check-and-set on [actions] is the whole
     * guard: a second tap while one is live is dropped rather than queued.
     */
    private fun claimAction(depositId: PeerDepositId, kind: PeerOrderActionKind): Boolean {
        val before =
            actions.getAndUpdate { current ->
                if (current[depositId]?.isRunning == true) {
                    current
                } else {
                    current +
                        (
                            depositId to
                                PeerOrderActionRun(
                                    depositId = depositId,
                                    kind = kind,
                                    latest = PeerCashOutStatus.Idle,
                                    isRunning = true,
                                )
                        )
                }
            }
        return before[depositId]?.isRunning != true
    }

    /**
     * A finished action is kept, stamped, rather than dropped: the order poll has not caught up yet
     * and the screen needs something to hold its buttons closed until it does. A failure keeps the
     * reason too, because the poll shows the same numbers either way and only the error says why.
     */
    private fun settleAction(depositId: PeerDepositId) {
        actions.update { current ->
            val run = current[depositId] ?: return@update current
            current + (depositId to run.copy(isRunning = false, settledAtMillis = System.currentTimeMillis()))
        }
    }

    override fun clearOrderAction(depositId: PeerDepositId) {
        actions.update { current ->
            if (current[depositId]?.isRunning == true) current else current - depositId
        }
    }

    override fun onDepositReconciled(id: PeerCashOutId, depositId: PeerDepositId) {
        state.update { current ->
            val run = current[id] ?: return@update current
            if (run.isDriving || run.depositId != null) return@update current
            current + (id to run.copy(reconciledDepositId = depositId))
        }
    }

    /**
     * [PeerCashOutRun.isDriving] is what separates an attempt this process is carrying from one it
     * merely remembers. Reconciliation leaves the first alone — the runner owns its own checkpoint —
     * and is the only thing that can finish the second.
     */
    private fun launchDrive(
        id: PeerCashOutId,
        request: PeerCashOutRequest,
        checkpoint: PeerCashOutCheckpoint?,
    ): Job {
        setDriving(id, isDriving = true)
        return scope.launch {
            try {
                drive(id, request, checkpoint)
            } finally {
                setDriving(id, isDriving = false)
            }
        }
    }

    private fun setDriving(id: PeerCashOutId, isDriving: Boolean) {
        state.update { current ->
            val run = current[id] ?: return@update current
            current + (id to run.copy(isDriving = isDriving))
        }
    }

    /**
     * Rethrows cancellation rather than folding it into "no checkpoint". A wipe cancels this while
     * it waits on storage, and treating that as an absent record would start a fresh attempt on the
     * wallet that has just been deleted.
     */
    private suspend fun readCheckpoint(id: PeerCashOutId): PeerCashOutCheckpoint? =
        try {
            checkpointStorage.get(id)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            Twig.warn(e) { "PeerCashOutRepository: checkpoint read failed" }
            null
        }

    /**
     * The payee this attempt was opened for, never the platform's current one. Registration is per
     * handle and the stored handle is whatever the user last typed, so reading it here would let a
     * retry of a Revolut cash-out pay a revtag the original never named.
     *
     * A checkpoint alone is enough to recover: it carries the registered hash, and the hash is the
     * only part of a payee that has to reach the chain.
     */
    private fun resumeRequest(id: PeerCashOutId, checkpoint: PeerCashOutCheckpoint?): PeerCashOutRequest? {
        requests[id]?.let { original ->
            return original.copy(cachedPayeeHash = checkpoint?.payeeHash ?: original.cachedPayeeHash)
        }
        return checkpoint?.let {
            runCatching {
                PeerCashOutRequest(
                    platform = it.platform,
                    handle = null,
                    currencies = it.currencies,
                    amount = it.amount,
                    cachedPayeeHash = it.payeeHash,
                )
            }.onFailure { error -> Twig.warn(error) { "PeerCashOutRepository: cannot rebuild the request" } }
                .getOrNull()
        }
    }

    /**
     * Stops at the live order rather than polling it forever: from there the chain is the record and
     * the indexer list is the surface. The record itself is kept so the progress screen can still be
     * re-entered.
     */
    private suspend fun drive(
        id: PeerCashOutId,
        request: PeerCashOutRequest,
        checkpoint: PeerCashOutCheckpoint?,
    ) {
        val persister = PeerCashOutCheckpointPersister(storage = checkpointStorage, id = id, request = request)
        persister.seedFrom(checkpoint)
        val flow = if (checkpoint != null) orchestrator.resume(checkpoint) else orchestrator.createOrder(request)
        flow
            .transformWhile { status ->
                emit(status)
                status !is PeerCashOutStatus.OrderLive
            }.collect { status ->
                persister.onStatus(status)
                rememberPayee(status, request)
                state.update { current ->
                    val run = current[id] ?: return@update current
                    current + (id to run.copy(statuses = run.statuses + status))
                }
            }
    }

    private suspend fun rememberPayee(status: PeerCashOutStatus, request: PeerCashOutRequest) {
        val hash = (status as? PeerCashOutStatus.ValidatingPayee)?.payeeHash ?: return
        val handle = request.handle ?: return
        payeeHandleProvider.store(platform = request.platform, handle = handle, hash = hash)
    }

    private fun seedOf(
        id: PeerCashOutId,
        request: PeerCashOutRequest,
        checkpoint: PeerCashOutCheckpoint?,
    ): PeerCashOutRun =
        PeerCashOutRun(
            id = id,
            platform = request.platform,
            amount = request.amount,
            currencies = request.currencies,
            statuses = emptyList(),
            startedAtMillis = checkpoint?.createdAtMillis ?: System.currentTimeMillis(),
        )

    /** An attempt from a previous process. Nothing drives it until the progress screen resumes it. */
    private fun dormantOf(checkpoint: PeerCashOutCheckpoint): PeerCashOutRun =
        PeerCashOutRun(
            id = checkpoint.id,
            platform = checkpoint.platform,
            amount = checkpoint.amount,
            currencies = checkpoint.currencies,
            statuses = emptyList(),
            startedAtMillis = checkpoint.createdAtMillis,
        )

    private fun record(run: PeerCashOutRun) {
        state.update { it + (run.id to run) }
    }

    /** Records an attempt only if its id is free, so a repeat start never rewinds one in flight. */
    private fun claim(run: PeerCashOutRun): Boolean {
        val before = state.getAndUpdate { current -> if (run.id in current) current else current + (run.id to run) }
        return run.id !in before
    }

    private fun forget(id: PeerCashOutId) {
        state.update { it - id }
    }
}
