// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.exception.SdkException
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.backgroundScope
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.atomicswap.AtomicSwapException
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.offramp.atomicswap.AtomicSwapActivity
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.AtomicSwapHttpException
import xyz.justzappit.offramp.atomicswap.AtomicSwapService
import xyz.justzappit.offramp.atomicswap.AtomicSwapStep
import xyz.justzappit.offramp.atomicswap.SwapDirection
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Where the loop has a conversion: what it waits for or does now, and what holds it up. */
data class SwapProgress(
    val index: Int? = null,
    val wait: AtomicSwapStep.Waiting? = null,
    val activity: AtomicSwapActivity? = null,
    /** What holds the conversion up: Zcash to wait for, or a problem once steps have failed for a while. */
    val hold: SwapHold? = null,
    /** When the steps failing in a row began to. */
    val failingSince: Long? = null,
    val confirmations: Int? = null,
    /** Picked up after the app was closed, and no step has finished since. */
    val resuming: Boolean = false,
) {
    val problem: AtomicSwapProblem? get() = hold as? AtomicSwapProblem

    val zcashWait: ZcashWait? get() = hold as? ZcashWait
}

/** What holds a conversion up; it's tried again by itself either way. */
sealed interface SwapHold

enum class AtomicSwapProblem : SwapHold {
    RELAYER_UNREACHABLE,
    ETHEREUM_UNREACHABLE,
    RAILGUN_CLOSED,
    CLAIM_TURN,
    ZCASH_WALLET,

    /** The wallet can't pay the deposit within what the user approved; past its window, nothing is sent. */
    DEPOSIT_UNPAYABLE,

    /** The Zcash network refused a transaction the conversion keeps sending. */
    ZCASH_REJECTED,

    /** What's on the chain isn't what the conversion agreed: nothing more is done with it than bringing funds back. */
    MISMATCH,
    UNEXPECTED,
}

/** Zcash to wait for, never a problem: nobody is asked to help. */
enum class ZcashWait : SwapHold {
    /** The wallet is still connecting or syncing, or hasn't sent a transaction yet. */
    SYNCING,

    /** ZEC paid in isn't all confirmed yet. */
    CONFIRMATIONS,
}

/** A direction's conversions as the app runs them, in the foreground or the background. */
interface SwapConversionLifecycle {
    /** Keeps a conversion under way advancing. Only the foreground may start the worker's service. */
    fun resume(isForeground: Boolean)

    fun retryNow()

    suspend fun isUnderWay(): Boolean

    suspend fun awaitSettled()

    /** Stops every conversion's work and forgets it in memory, for a wallet about to be wiped. */
    suspend fun reset()
}

/** One direction's conversions, as [SwapLoop] advances them. */
internal interface SwapConversions<R : Any> {
    val direction: SwapDirection

    /** The wallet session work begins in now; a reset ends it, and whatever runs in it after keeps nothing. */
    val session: Long

    /** The active conversion; one the store can't read shows as none. */
    val active: Flow<R?>

    /** The conversion under way, if there is one. */
    suspend fun underWay(): R?

    fun isUnderWay(record: R): Boolean

    fun indexOf(record: R): Int

    /** Only the user can take [record] further. */
    fun needsYou(record: R): Boolean

    /** One step of [record] in [session], which it can't outlast; [onActivity] hears of a slow one before it starts. */
    suspend fun advance(
        record: R,
        session: Long,
        onActivity: (AtomicSwapActivity) -> Unit,
    ): SwapLoopStep

    /** Nothing is under way any more. */
    fun settled()

    /** Ends the wallet's session and forgets its conversions, for a wallet about to be wiped. */
    suspend fun reset()
}

internal sealed interface SwapLoopStep {
    /** Nothing more to do before [nap] is up or the loop is nudged. [needsYou] when only the user can go on. */
    data class Waiting(
        val nap: Duration,
        val wait: AtomicSwapStep.Waiting? = null,
        val confirmations: Int? = null,
        val needsYou: Boolean = false,
    ) : SwapLoopStep

    /** Over, and its end announced. */
    data object Over : SwapLoopStep
}

/** Advances the conversion under way until it's over, retrying failed steps and saying why they failed. */
internal class SwapLoop<R : Any>(
    private val conversions: SwapConversions<R>,
    private val notifier: AtomicSwapNotifier,
    private val scope: CoroutineScope,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val mutableProgress = MutableStateFlow(SwapProgress())
    val progress: StateFlow<SwapProgress> = mutableProgress.asStateFlow()
    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /** Runs the loop in [session], unless that ended; whether a loop runs in it now. */
    fun start(
        resuming: Boolean,
        session: Long = conversions.session,
    ): Boolean =
        synchronized(this) {
            val isCurrent = session == conversions.session && session != AtomicSwapSessions.CLOSED
            if (isCurrent && job?.isActive != true) job = scope.launch(restarts(session)) { run(resuming, session) }
            isCurrent
        }

    /** Stops the loop and forgets what it said, for a wallet about to be wiped. */
    suspend fun reset() {
        synchronized(this) { job.also { job = null } }?.cancelAndJoin()
        conversions.settled()
        mutableProgress.value = SwapProgress()
    }

    fun nudge() {
        nudges.trySend(Unit)
    }

    suspend fun settle() {
        conversions.settled()
        notifier.clearNeedsYou()
        mutableProgress.update {
            it.copy(wait = null, activity = null, hold = null, failingSince = null, resuming = false)
        }
    }

    private fun restarts(session: Long) =
        CoroutineExceptionHandler { _, e ->
            Twig.error(e) { "${conversions.direction} swap: the loop stopped" }
            mutableProgress.update { it.failing(AtomicSwapProblem.UNEXPECTED) }
            scope.launch {
                delay(RESTART_DELAY)
                start(resuming = true, session)
            }
        }

    private suspend fun run(
        resuming: Boolean,
        session: Long
    ) {
        var record = conversions.underWay()
        record?.let {
            val index = conversions.indexOf(it)
            // Restarted after it stopped, it goes on counting how long steps have failed.
            mutableProgress.update { kept ->
                val failingSince = kept.failingSince.takeIf { kept.index == index }
                SwapProgress(index, failingSince = failingSince, resuming = resuming)
            }
        }
        val attention = Attention(askedForYou = record?.let(conversions::needsYou) == true)
        while (record != null && session == conversions.session) {
            when (val step = step(record, session)) {
                null -> {
                    attention.failed()
                    nap(RETRY_DELAY)
                }

                is SwapLoopStep.Waiting -> {
                    attention.waiting(step.needsYou)
                    nap(step.nap)
                }

                SwapLoopStep.Over -> {
                    Unit
                }
            }
            record = conversions.underWay()
        }
        settle()
    }

    /** When to ask for the user. Only a change is announced: a conversion found waiting on them already was. */
    private inner class Attention(
        private var askedForYou: Boolean,
    ) {
        // Waiting for the maker's turn to pass, or for Railgun to reopen: opening the app changes neither.
        fun failed() {
            val current = progress.value
            val failingFor = nowSeconds() - (current.failingSince ?: nowSeconds())
            val canHelp = current.problem?.let { it !in OUT_OF_THE_USERS_HANDS } == true
            if (!askedForYou && canHelp && failingFor >= NEEDS_YOU_AFTER.inWholeSeconds) {
                notifier.needsYou(conversions.direction)
                askedForYou = true
            }
        }

        fun waiting(needsYou: Boolean) {
            if (needsYou == askedForYou) return
            if (needsYou) notifier.needsYou(conversions.direction) else notifier.clearNeedsYou()
            askedForYou = needsYou
        }
    }

    /** One step, or null when it failed and should be tried again. Zcash to wait for is waited for like any step. */
    private suspend fun step(
        record: R,
        session: Long
    ): SwapLoopStep? {
        val index = conversions.indexOf(record)
        return catchingSwapFailures(
            onFailure = { e, hold ->
                Twig.warn(e) { "${conversions.direction} swap: a step failed" }
                when (hold) {
                    is ZcashWait -> {
                        mutableProgress.update {
                            it.copy(index = index, activity = null, hold = hold, failingSince = null, resuming = false)
                        }
                        SwapLoopStep.Waiting(RETRY_DELAY, needsYou = conversions.needsYou(record))
                    }

                    is AtomicSwapProblem -> {
                        mutableProgress.update { it.failing(hold).copy(index = index, resuming = false) }
                        null
                    }
                }
            },
        ) {
            val step = conversions.advance(record, session, ::show)
            val waiting = step as? SwapLoopStep.Waiting
            mutableProgress.update {
                it.copy(
                    index = index,
                    wait = waiting?.wait,
                    activity = null,
                    hold = null,
                    failingSince = null,
                    confirmations = if (waiting != null) waiting.confirmations else it.confirmations,
                    resuming = false,
                )
            }
            step
        }
    }

    private fun show(activity: AtomicSwapActivity) = mutableProgress.update { it.copy(activity = activity) }

    // The next try often gets past a failure: a problem shows only once steps have failed for a while in a row.
    private fun SwapProgress.failing(problem: AtomicSwapProblem): SwapProgress {
        val now = nowSeconds()
        val since = failingSince ?: now
        return copy(
            activity = null,
            hold = problem.takeIf { now - since >= PROBLEM_AFTER.inWholeSeconds },
            failingSince = since,
        )
    }

    private suspend fun nap(duration: Duration) {
        withTimeoutOrNull(duration) { nudges.receive() }
    }

    private companion object {
        val RETRY_DELAY = 15.seconds
        val RESTART_DELAY = 30.seconds
        val PROBLEM_AFTER = 1.minutes
        val NEEDS_YOU_AFTER = 5.minutes
        val OUT_OF_THE_USERS_HANDS =
            setOf(
                AtomicSwapProblem.CLAIM_TURN,
                AtomicSwapProblem.RAILGUN_CLOSED,
                AtomicSwapProblem.ZCASH_REJECTED,
                AtomicSwapProblem.MISMATCH,
            )
    }
}

/** Runs one direction's conversions: [loop] in this process, and the worker and wakes that keep it alive. */
internal class SwapConversionRunner<R : Any>(
    private val conversions: SwapConversions<R>,
    private val scheduler: AtomicSwapScheduler,
    private val notifier: AtomicSwapNotifier,
    private val scope: CoroutineScope,
    private val isAvailable: Boolean,
) : SwapConversionLifecycle {
    val loop = SwapLoop(conversions, notifier, scope)

    override fun resume(isForeground: Boolean) = resume(isForeground, conversions.session)

    /** [resume] for work begun in [session]: nothing picks up once a reset ended it. */
    fun resume(
        isForeground: Boolean,
        session: Long,
    ) {
        if (!isAvailable) return
        scope.launch {
            if (conversions.underWay() == null || !loop.start(resuming = true, session)) return@launch
            if (isForeground) scheduler.runNow() else scheduler.runIfIdle()
        }
    }

    override fun retryNow() = loop.nudge()

    override suspend fun isUnderWay(): Boolean = isAvailable && conversions.underWay() != null

    override suspend fun awaitSettled() {
        conversions.active.first { it == null || !conversions.isUnderWay(it) }
    }

    override suspend fun reset() {
        loop.reset()
        conversions.reset()
        scheduler.cancel()
        notifier.clear()
    }
}

/** [block]'s result, or [onFailure]'s for a failure a step expects; cancellation is never one. */
internal inline fun <T> catchingSwapFailures(
    onFailure: (Exception, SwapHold) -> T,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AtomicSwapBlockedException) {
        onFailure(e, e.reason.hold())
    } catch (e: AtomicSwapHttpException) {
        onFailure(e, e.service.problem())
    } catch (e: RpcException) {
        onFailure(e, AtomicSwapProblem.ETHEREUM_UNREACHABLE)
    } catch (e: SdkException) {
        onFailure(e, AtomicSwapProblem.ZCASH_WALLET)
    } catch (e: IOException) {
        onFailure(e, AtomicSwapProblem.ZCASH_WALLET)
    } catch (e: AtomicSwapException) {
        onFailure(e, AtomicSwapProblem.UNEXPECTED)
    } catch (e: StoreCorruptedException) {
        onFailure(e, AtomicSwapProblem.UNEXPECTED)
    } catch (e: IllegalStateException) {
        onFailure(e, AtomicSwapProblem.UNEXPECTED)
    } catch (e: IllegalArgumentException) {
        onFailure(e, AtomicSwapProblem.UNEXPECTED)
    }

// The loop reaches a maker only to accept a reverse swap, and keeps trying: there's nothing more to say about it.
internal fun AtomicSwapService.problem() =
    when (this) {
        AtomicSwapService.RELAYER -> AtomicSwapProblem.RELAYER_UNREACHABLE
        AtomicSwapService.MAKER -> AtomicSwapProblem.UNEXPECTED
    }

internal fun AtomicSwapBlock.hold(): SwapHold =
    when (this) {
        AtomicSwapBlock.RAILGUN_CLOSED -> {
            AtomicSwapProblem.RAILGUN_CLOSED
        }

        AtomicSwapBlock.CLAIM_LOCK_LAPSING -> {
            AtomicSwapProblem.CLAIM_TURN
        }

        AtomicSwapBlock.CHAIN_LAGGING, AtomicSwapBlock.CHAIN_UNREADABLE -> {
            AtomicSwapProblem.ETHEREUM_UNREACHABLE
        }

        AtomicSwapBlock.RELAYER_FEE, AtomicSwapBlock.WRONG_DEPLOYMENT -> {
            AtomicSwapProblem.RELAYER_UNREACHABLE
        }

        AtomicSwapBlock.ZCASH_UNAVAILABLE -> {
            ZcashWait.SYNCING
        }

        AtomicSwapBlock.DEPOSIT_UNCONFIRMED -> {
            ZcashWait.CONFIRMATIONS
        }

        AtomicSwapBlock.DEPOSIT_UNPAYABLE -> {
            AtomicSwapProblem.DEPOSIT_UNPAYABLE
        }

        AtomicSwapBlock.ZCASH_REJECTED -> {
            AtomicSwapProblem.ZCASH_REJECTED
        }

        AtomicSwapBlock.MISMATCH -> {
            AtomicSwapProblem.MISMATCH
        }

        AtomicSwapBlock.SWAP_UNDER_WAY,
        AtomicSwapBlock.QUOTE_EXPIRED,
        AtomicSwapBlock.DEADLINE_PASSED,
        AtomicSwapBlock.FUNDING_COST_CHANGED,
        AtomicSwapBlock.FUNDING_UNAVAILABLE,
        AtomicSwapBlock.UNDER_WAY_ON_CHAIN,
        AtomicSwapBlock.INDICES_IN_USE,
        AtomicSwapBlock.MAKER_BUSY,
        AtomicSwapBlock.TOKENS_EXHAUSTED,
        AtomicSwapBlock.TOKENS_REFUSED,
        AtomicSwapBlock.TOKENS_UNAVAILABLE -> {
            AtomicSwapProblem.UNEXPECTED
        }
    }

internal fun swapScope(): CoroutineScope = backgroundScope("Atomic swap")
