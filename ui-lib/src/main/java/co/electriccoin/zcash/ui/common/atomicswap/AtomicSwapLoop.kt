// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.exception.SdkException
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.atomicswap.AtomicSwapException
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.offramp.atomicswap.AtomicSwapActivity
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.AtomicSwapHttpException
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.AtomicSwapService
import xyz.justzappit.offramp.atomicswap.AtomicSwapStep
import xyz.justzappit.offramp.atomicswap.AtomicSwapWait
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal data class AtomicSwapProgress(
    val index: Int? = null,
    val wait: AtomicSwapStep.Waiting? = null,
    val activity: AtomicSwapActivity? = null,
    val problem: AtomicSwapProblem? = null,
    val confirmations: Int? = null,
    val resuming: Boolean = false,
)

/** Advances the swap under way until it finishes, retrying failed steps and saying why they failed. */
internal class AtomicSwapLoop(
    private val sessions: AtomicSwapSessions,
    private val driverLock: Mutex,
    private val store: AtomicSwapStoreImpl,
    private val zcash: AtomicSwapZcashInfo,
    private val scheduler: AtomicSwapScheduler,
    private val notifier: AtomicSwapNotifier,
    private val scope: CoroutineScope,
) {
    private val mutableProgress = MutableStateFlow(AtomicSwapProgress())
    val progress: StateFlow<AtomicSwapProgress> = mutableProgress.asStateFlow()
    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var wakesFor: Pair<Long, Long>? = null
    private val restarts =
        CoroutineExceptionHandler { _, e ->
            Twig.error(e) { "Atomic swap: the loop stopped" }
            mutableProgress.update { it.copy(activity = null, problem = AtomicSwapProblem.UNEXPECTED) }
            scope.launch {
                delay(RESTART_DELAY)
                start(resuming = true)
            }
        }

    fun start(resuming: Boolean) {
        synchronized(this) {
            if (job?.isActive == true) return
            job = scope.launch(restarts) { run(resuming) }
        }
    }

    fun nudge() {
        nudges.trySend(Unit)
    }

    suspend fun settle() {
        wakesFor = null
        scheduler.cancelWakes()
        notifier.clearNeedsYou()
        mutableProgress.update { it.copy(wait = null, activity = null, problem = null, resuming = false) }
    }

    private suspend fun run(resuming: Boolean) {
        var failingSince: Long? = null
        var notifiedNeedsYou = false
        var record = store.active()?.takeUnless { it.finished }
        record?.let { mutableProgress.value = AtomicSwapProgress(it.index, resuming = resuming) }
        while (record != null) {
            when (val step = step(record)) {
                null -> {
                    val since = failingSince ?: now().also { failingSince = it }
                    val canHelp = progress.value.problem?.let { it !in OUT_OF_THE_USERS_HANDS } == true
                    if (canHelp && !notifiedNeedsYou && now() - since >= NEEDS_YOU_AFTER.inWholeSeconds) {
                        notifier.needsYou()
                        notifiedNeedsYou = true
                    }
                    nap(RETRY_DELAY)
                }

                is AtomicSwapStep.Finished -> {
                    notifier.finished(step.outcome)
                }

                is AtomicSwapStep.Waiting -> {
                    failingSince = null
                    if (notifiedNeedsYou) notifier.clearNeedsYou()
                    notifiedNeedsYou = false
                    wake(step)
                    mutableProgress.update { it.copy(confirmations = confirmations(step)) }
                    nap(pollInterval(step.reason))
                }
            }
            record = store.active()?.takeUnless { it.finished }
        }
        settle()
    }

    /** One advance, or null when it failed and should be tried again. */
    private suspend fun step(record: AtomicSwapRecord): AtomicSwapStep? =
        try {
            val step =
                driverLock.withLock {
                    val current = store.active()?.takeIf { it.index == record.index } ?: record
                    sessions
                        .forRecord(
                            current
                        ).driver
                        .advance(current) { activity -> mutableProgress.update { it.copy(activity = activity) } }
                }
            mutableProgress.update {
                it.copy(
                    index = record.index,
                    wait = step as? AtomicSwapStep.Waiting,
                    activity = null,
                    problem = null,
                    resuming = false,
                )
            }
            step
        } catch (e: AtomicSwapBlockedException) {
            failed(record, e, e.reason.problem())
        } catch (e: AtomicSwapHttpException) {
            failed(record, e, e.service.problem())
        } catch (e: RpcException) {
            failed(record, e, AtomicSwapProblem.ETHEREUM_UNREACHABLE)
        } catch (e: SdkException) {
            failed(record, e, AtomicSwapProblem.ZCASH_WALLET)
        } catch (e: TimeoutCancellationException) {
            failed(record, e, AtomicSwapProblem.ZCASH_WALLET)
        } catch (e: IOException) {
            failed(record, e, AtomicSwapProblem.ZCASH_WALLET)
        } catch (e: AtomicSwapException) {
            failed(record, e, AtomicSwapProblem.UNEXPECTED)
        } catch (e: StoreCorruptedException) {
            failed(record, e, AtomicSwapProblem.UNEXPECTED)
        } catch (e: IllegalStateException) {
            failed(record, e, AtomicSwapProblem.UNEXPECTED)
        }

    private fun failed(
        record: AtomicSwapRecord,
        e: Exception,
        problem: AtomicSwapProblem
    ): AtomicSwapStep? {
        Twig.warn(e) { "Atomic swap: a step failed" }
        mutableProgress.update { it.copy(index = record.index, activity = null, problem = problem) }
        return null
    }

    private fun wake(step: AtomicSwapStep.Waiting) {
        val t0 = step.t0
        val t1 = step.t1
        if (t0 != null && t1 != null && wakesFor != t0 to t1) {
            scheduler.wakeAt(t0, t1)
            wakesFor = t0 to t1
        }
    }

    private suspend fun confirmations(step: AtomicSwapStep.Waiting): Int? {
        val txId = store.active()?.depositTxId
        return if (step.reason == AtomicSwapWait.CONFIRMING && txId != null) {
            try {
                zcash.confirmations(txId)
            } catch (e: SdkException) {
                Twig.info { "Atomic swap: no confirmation count, ${e.message}" }
                null
            }
        } else {
            null
        }
    }

    private suspend fun nap(duration: Duration) {
        withTimeoutOrNull(duration) { nudges.receive() }
    }

    private fun now() = Clock.System.now().epochSeconds

    private companion object {
        val RETRY_DELAY = 15.seconds
        val RESTART_DELAY = 30.seconds
        val NEEDS_YOU_AFTER = 5.minutes

        // Waiting for the maker's turn to pass, or for Railgun to reopen: opening the app changes neither.
        val OUT_OF_THE_USERS_HANDS = setOf(AtomicSwapProblem.CLAIM_TURN, AtomicSwapProblem.RAILGUN_CLOSED)

        fun pollInterval(reason: AtomicSwapWait) =
            when (reason) {
                AtomicSwapWait.OPENING -> 5.seconds
                AtomicSwapWait.CONFIRMING -> 20.seconds
                AtomicSwapWait.DEPOSIT_UNSETTLED -> 30.seconds
            }

        fun AtomicSwapService.problem() =
            when (this) {
                AtomicSwapService.RELAYER -> AtomicSwapProblem.RELAYER_UNREACHABLE
                AtomicSwapService.MAKER -> AtomicSwapProblem.MAKER_UNREACHABLE
            }

        fun AtomicSwapBlock.problem() =
            when (this) {
                AtomicSwapBlock.RAILGUN_CLOSED -> AtomicSwapProblem.RAILGUN_CLOSED

                AtomicSwapBlock.CLAIM_LOCK_LAPSING -> AtomicSwapProblem.CLAIM_TURN

                AtomicSwapBlock.CHAIN_LAGGING -> AtomicSwapProblem.ETHEREUM_UNREACHABLE

                AtomicSwapBlock.RELAYER_FEE, AtomicSwapBlock.WRONG_DEPLOYMENT -> AtomicSwapProblem.RELAYER_UNREACHABLE

                AtomicSwapBlock.SWAP_UNDER_WAY,
                AtomicSwapBlock.QUOTE_EXPIRED,
                AtomicSwapBlock.UNDER_WAY_ON_CHAIN -> AtomicSwapProblem.UNEXPECTED
            }
    }
}
