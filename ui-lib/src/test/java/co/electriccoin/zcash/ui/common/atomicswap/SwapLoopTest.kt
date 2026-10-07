// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.privateusd.toUsd
import co.electriccoin.zcash.ui.screen.privateusd.toZec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.offramp.atomicswap.AtomicSwapActivity
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.AtomicSwapDriver
import xyz.justzappit.offramp.atomicswap.AtomicSwapHttpException
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.AtomicSwapService
import xyz.justzappit.offramp.atomicswap.AtomicSwapStep
import xyz.justzappit.offramp.atomicswap.AtomicSwapWait
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapDriver
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.SwapDirection
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SwapLoopTest {
    private val notifier = mockk<AtomicSwapNotifier>(relaxed = true)

    @Test
    fun `a failed step is tried again, says why only once it keeps failing, and not after one goes through`() =
        runTest {
            val conversions = Scripted(*Array(5) { fail(unreachableRelayer()) }, waiting())
            val loop = loop(conversions)

            loop.start(resuming = false)
            runCurrent()
            assertNull(loop.progress.value.problem)
            assertEquals(1, conversions.advances)

            advanceTimeBy(PERSISTED)
            assertEquals(5, conversions.advances)
            assertEquals(AtomicSwapProblem.RELAYER_UNREACHABLE, loop.progress.value.problem)
            advanceTimeBy(RETRY)
            assertEquals(6, conversions.advances)
            assertNull(loop.progress.value.problem)
        }

    @Test
    fun `failing for five minutes asks the user once, and a step that goes through lets them off`() =
        runTest {
            val conversions = Scripted(*Array(25) { fail(unreachableRelayer()) }, waiting())
            val loop = loop(conversions)

            loop.start(resuming = false)
            advanceTimeBy(4.minutes + 50.seconds)
            verify(exactly = 0) { notifier.needsYou(any()) }
            advanceTimeBy(30.seconds)
            verify(exactly = 1) { notifier.needsYou(SwapDirection.FORWARD) }
            advanceTimeBy(1.minutes)
            verify(exactly = 1) { notifier.needsYou(any()) }

            advanceTimeBy(2.minutes)
            assertNull(loop.progress.value.problem)
            verify { notifier.clearNeedsYou() }
        }

    @Test
    fun `a claim lock lapsing into the maker's turn is waited out without asking the user, who can't help`() =
        runTest {
            val lapsing = AtomicSwapBlockedException(AtomicSwapBlock.CLAIM_LOCK_LAPSING, "the maker has the turn")
            val loop = loop(Scripted(*Array(60) { fail(lapsing) }))

            loop.start(resuming = false)
            advanceTimeBy(10.minutes)

            assertEquals(AtomicSwapProblem.CLAIM_TURN, loop.progress.value.problem)
            verify(exactly = 0) { notifier.needsYou(any()) }
        }

    @Test
    fun `a loop that keeps stopping unexpectedly says so, and picks the conversion up again`() =
        runTest {
            val conversions = Scripted(*Array(3) { fail(UnsupportedOperationException("a bug")) }, waiting())
            val loop = loop(conversions)

            loop.start(resuming = false)
            runCurrent()
            assertNull(loop.progress.value.problem)

            advanceTimeBy(RESTART * 2 + 1.seconds)
            assertEquals(3, conversions.advances)
            assertEquals(AtomicSwapProblem.UNEXPECTED, loop.progress.value.problem)

            advanceTimeBy(RESTART)
            assertEquals(4, conversions.advances)
            assertNull(loop.progress.value.problem)
            assertFalse(loop.progress.value.resuming)
        }

    @Test
    fun `a nudge cuts a nap short`() =
        runTest {
            val conversions = Scripted(waiting(), waiting())
            val loop = loop(conversions)

            loop.start(resuming = false)
            runCurrent()
            loop.nudge()
            runCurrent()

            assertEquals(2, conversions.advances)
        }

    @Test
    fun `a conversion that's over leaves the loop settled`() =
        runTest {
            val conversions = Scripted(waiting(), over())
            val loop = loop(conversions)

            loop.start(resuming = true)
            runCurrent()
            assertFalse(loop.progress.value.resuming)
            loop.nudge()
            runCurrent()

            assertEquals(1, conversions.settles)
            verify { notifier.clearNeedsYou() }
            assertNull(loop.progress.value.wait)
        }

    @Test
    fun `cancellation is never taken for a failed step`() =
        runTest {
            assertFailsWith<CancellationException> {
                catchingSwapFailures(onFailure = { _, _ -> error("a failure") }) { throw CancellationException("stop") }
            }
            val refused =
                catchingSwapFailures(onFailure = { _, problem -> problem }) { throw IllegalStateException("x") }
            assertEquals(AtomicSwapProblem.UNEXPECTED, refused)
        }

    @Test
    fun `a forward swap waiting on its deadlines wakes the app around them once, and its end is announced`() =
        runTest {
            val scheduler = mockk<AtomicSwapScheduler>(relaxed = true)
            val driver = mockk<AtomicSwapDriver>()
            val waiting = AtomicSwapStep.Waiting(AtomicSwapWait.CONFIRMING, t0 = 1_000, t1 = 2_000)
            val store = ForwardRecords(toUsd(index = 4, at = 10, outcome = null))
            var advances = 0
            coEvery { driver.advance(any(), any()) } coAnswers {
                advances++
                if (advances < 3) {
                    waiting
                } else {
                    store.record = toUsd(index = 4, at = 10, AtomicSwapOutcome.Paid)
                    AtomicSwapStep.Finished(AtomicSwapOutcome.Paid)
                }
            }
            val sessions =
                mockk<AtomicSwapSessions> {
                    every { session } returns 0L
                    every { forward(any<AtomicSwapRecord>(), any()) } returns driver
                }
            val zcash = mockk<AtomicSwapZcashInfo>(relaxed = true)
            val payouts = mockk<AtomicSwapPayouts>(relaxed = true)
            val tokens = mockk<AtomicSwapTokens>(relaxed = true)
            val conversions =
                AtomicSwapConversions(sessions, Mutex(), store, zcash, scheduler, notifier, payouts, tokens)
            val loop = loop(conversions)

            loop.start(resuming = false)
            runCurrent()
            assertEquals(waiting, loop.progress.value.wait)
            repeat(2) {
                loop.nudge()
                runCurrent()
            }

            verify(exactly = 1) { scheduler.wakeAt(1_000, 2_000) }
            verify {
                notifier.finished(
                    SwapDirection.FORWARD,
                    R.string.private_usd_notification_paid_title,
                    R.string.private_usd_notification_paid_body
                )
            }
            verify { scheduler.cancelWakes() }
        }

    @Test
    fun `a reverse conversion's failures show as typed problems once they last, and it's looked at again`() =
        runTest {
            val store = ReverseRecords(toZec(index = 1, ReversePhase.RECEIVING_ZEC))
            val driver = mockk<ReverseSwapDriver>()
            coEvery { driver.advance() } throws RpcException.TransportError("eth_call", IOException("offline"))
            val loop = loop(ReverseSwapConversions(sessions(driver), store, notifier, mockk(relaxed = true)))

            loop.start(resuming = false)
            advanceTimeBy(PERSISTED)
            assertEquals(AtomicSwapProblem.ETHEREUM_UNREACHABLE, loop.progress.value.problem)
            assertEquals(1, loop.progress.value.index)

            coEvery { driver.advance() } returns store.record
            advanceTimeBy(RETRY + 1.seconds)
            assertNull(loop.progress.value.problem)
        }

    @Test
    fun `a reverse conversion asks for the user's approval once, and not again when picked up after a restart`() =
        runTest {
            val store = ReverseRecords(toZec(index = 1, ReversePhase.RECEIVING_ZEC))
            val driver = mockk<ReverseSwapDriver>()
            coEvery { driver.advance() } answers { store.moveTo(ReversePhase.AWAITING_READY) }
            val loop = loop(ReverseSwapConversions(sessions(driver), store, notifier, mockk(relaxed = true)))

            loop.start(resuming = false)
            runCurrent()
            verify(exactly = 1) { notifier.needsYou(SwapDirection.REVERSE) }

            loop.reset()
            loop.start(resuming = true)
            runCurrent()
            verify(exactly = 1) { notifier.needsYou(any()) }

            coEvery { driver.advance() } answers { store.moveTo(ReversePhase.SETTLING) }
            loop.nudge()
            runCurrent()
            verify { notifier.clearNeedsYou() }
        }

    @Test
    fun `a reverse conversion's end is announced in its own words`() =
        runTest {
            val store = ReverseRecords(toZec(index = 1, ReversePhase.RECEIVING))
            val driver = mockk<ReverseSwapDriver>()
            coEvery { driver.advance() } answers { store.moveTo(ReversePhase.COMPLETE) }
            val loop = loop(ReverseSwapConversions(sessions(driver), store, notifier, mockk(relaxed = true)))

            loop.start(resuming = false)
            runCurrent()

            verify { notifier.finished(SwapDirection.REVERSE, R.string.reverse_title, R.string.reverse_complete) }
            assertNull(loop.progress.value.problem)
        }

    @Test
    fun `a reverse loop that stops unexpectedly restarts`() =
        runTest {
            val store = ReverseRecords(toZec(index = 1, ReversePhase.SETTLING))
            val driver = mockk<ReverseSwapDriver>()
            coEvery { driver.advance() } throws UnsupportedOperationException("a bug") andThen store.record
            val loop = loop(ReverseSwapConversions(sessions(driver), store, notifier, mockk(relaxed = true)))

            loop.start(resuming = false)
            runCurrent()
            assertNull(loop.progress.value.problem)

            advanceTimeBy(RESTART + 1.seconds)
            coVerify(exactly = 2) { driver.advance() }
            assertNull(loop.progress.value.problem)
        }

    // Under a supervisor, as the app's swap scope is, and stopped with the test.
    private fun <R : Any> TestScope.loop(conversions: SwapConversions<R>): SwapLoop<R> {
        val context = backgroundScope.coroutineContext
        val scope = CoroutineScope(context + SupervisorJob(context[Job]))
        return SwapLoop(conversions, notifier, scope) { testScheduler.currentTime / MILLIS }
    }

    private fun sessions(driver: ReverseSwapDriver) =
        mockk<AtomicSwapSessions> {
            every { session } returns 0L
            every { reverse(any(), any()) } returns driver
        }

    /** A conversion whose steps come out as scripted, the last one again and again. */
    private class Scripted(
        vararg steps: suspend () -> SwapLoopStep,
    ) : SwapConversions<Int> {
        private val script = steps.toMutableList()
        private var over = false
        var advances = 0
        var settles = 0

        override val direction = SwapDirection.FORWARD

        override val session = 0L

        override val active = MutableStateFlow<Int?>(1)

        override suspend fun underWay(): Int? = 1.takeUnless { over }

        override fun isUnderWay(record: Int) = !over

        override fun indexOf(record: Int) = record

        override fun needsYou(record: Int) = false

        override suspend fun advance(
            record: Int,
            session: Long,
            onActivity: (AtomicSwapActivity) -> Unit
        ): SwapLoopStep {
            advances++
            val step = (if (script.size > 1) script.removeAt(0) else script.single())()
            over = step == SwapLoopStep.Over
            return step
        }

        override fun settled() {
            settles++
        }

        override suspend fun reset() = Unit
    }

    private class ForwardRecords(
        var record: AtomicSwapRecord?,
    ) : AtomicSwapRecords {
        override val observeActive = MutableStateFlow(record)
        override val observeHistory = observeActive.map { listOfNotNull(it) }

        override suspend fun underWay() = record?.takeUnless { it.finished }

        override suspend fun update(
            index: Int,
            change: (AtomicSwapRecord) -> AtomicSwapRecord
        ) = error("not needed")

        override suspend fun takeIndex() = error("not needed")

        override suspend fun active() = record

        override suspend fun save(record: AtomicSwapRecord) {
            this.record = record
        }

        override suspend fun clear() {
            record = null
        }
    }

    private class ReverseRecords(
        var record: ReverseSwapRecord,
    ) : ReverseSwapRecords {
        override val observeActive = MutableStateFlow<ReverseSwapRecord?>(record)
        override val observeHistory = observeActive.map { listOfNotNull(it) }

        fun moveTo(phase: ReversePhase) = record.copy(phase = phase).also { record = it }

        override suspend fun underWay() = record.takeIf { it.underWay }

        override suspend fun active() = record

        override suspend fun find(index: Int) = record.takeIf { it.index == index }

        override suspend fun save(record: ReverseSwapRecord) {
            this.record = record
        }

        override suspend fun update(record: ReverseSwapRecord) {
            this.record = record
        }

        override suspend fun clear() = Unit
    }

    private companion object {
        const val MILLIS = 1_000
        val RETRY = 15.seconds
        val RESTART = 30.seconds

        // A minute of failing, and the step at its end.
        val PERSISTED = 1.minutes + 1.seconds

        fun unreachableRelayer() =
            AtomicSwapHttpException.Unreachable(AtomicSwapService.RELAYER, IOException("offline"))

        fun fail(e: Exception): suspend () -> SwapLoopStep = { throw e }

        fun waiting(nap: Duration = 1.hours): suspend () -> SwapLoopStep = { SwapLoopStep.Waiting(nap) }

        fun over(): suspend () -> SwapLoopStep = { SwapLoopStep.Over }
    }
}
