// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRecords
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRecords
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositStore
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendLog
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendGuard
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendStatus
import co.electriccoin.zcash.ui.common.privateusd.depositRecord
import co.electriccoin.zcash.ui.screen.privateusd.toUsd
import co.electriccoin.zcash.ui.screen.privateusd.toZec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.deposits.DepositRecord
import xyz.justzappit.offramp.deposits.DepositState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A wipe clears the records that carry a Shielded USD conversion, deposit or send to its end, and the funds in it
 * go with them: the gate refuses while one is under way, and while it cannot tell.
 */
class EnsureNoUnfinishedConversionsUseCaseTest {
    @Test
    fun `passes with nothing under way`() =
        runTest {
            gate()()
        }

    @Test
    fun `passes once every conversion, deposit and send is over`() =
        runTest {
            gate(
                forward = toUsd(index = 0, at = NOW - 600, AtomicSwapOutcome.Paid),
                reverse = toZec(index = 0, ReversePhase.COMPLETE),
                deposits = listOf(depositRecord(state = DepositState.REFUSED, createdAt = NOW - 60)),
            )()
        }

    @Test
    fun `blocks a conversion into USD under way`() =
        runTest {
            val blocked =
                assertFailsWith<UnfinishedConversionsException> {
                    gate(forward = toUsd(index = 0, at = NOW - 60, outcome = null))()
                }

            assertEquals(1, blocked.count)
        }

    @Test
    fun `blocks a conversion into ZEC under way, but not a quote only previewed`() =
        runTest {
            val blocked =
                assertFailsWith<UnfinishedConversionsException> {
                    gate(reverse = toZec(index = 0, ReversePhase.AWAITING_FUNDING))()
                }

            assertEquals(1, blocked.count)
            gate(reverse = toZec(index = 0, ReversePhase.QUOTED))()
        }

    @Test
    fun `blocks a deposit signed or posted that can still land`() =
        runTest {
            val blocked =
                assertFailsWith<UnfinishedConversionsException> {
                    gate(
                        deposits =
                            listOf(
                                depositRecord(index = 0, createdAt = NOW - 60),
                                depositRecord(index = 1, state = DepositState.SENT, createdAt = NOW - 60),
                            ),
                    )()
                }

            assertEquals(2, blocked.count)
        }

    @Test
    fun `a deposit whose authorization ran out is not under way`() =
        runTest {
            // The fixture's authorization is valid for an hour from its signing.
            gate(deposits = listOf(depositRecord(createdAt = NOW - 7_200)))()
        }

    @Test
    fun `blocks sends still proving or unsettled`() =
        runTest {
            val blocked = assertFailsWith<UnfinishedConversionsException> { gate(unsettledSends = 2)() }

            assertEquals(2, blocked.count)
        }

    @Test
    fun `counts everything under way together`() =
        runTest {
            val blocked =
                assertFailsWith<UnfinishedConversionsException> {
                    gate(
                        forward = toUsd(index = 0, at = NOW - 60, outcome = null),
                        reverse = toZec(index = 0, ReversePhase.SETTLING),
                        deposits = listOf(depositRecord(createdAt = NOW - 60)),
                        unsettledSends = 2,
                    )()
                }

            assertEquals(5, blocked.count)
        }

    @Test
    fun `blocks when a store cannot be read`() =
        runTest {
            val forward = mockk<AtomicSwapRecords>()
            coEvery { forward.active() } throws IllegalStateException("corrupt")
            val gate =
                EnsureNoUnfinishedConversionsUseCase(
                    forward = forward,
                    reverse = mockk { coEvery { active() } returns null },
                    refunds = mockk(),
                    deposits = mockk { coEvery { records() } returns emptyList() },
                    sends = mockk { coEvery { unsettledCount() } returns 0 },
                    spendGuard = guard(),
                    clock = FIXED_CLOCK,
                )

            val blocked = assertFailsWith<UnfinishedConversionsException> { gate() }

            assertNull(blocked.count)
        }

    @Test
    fun `a conversion being committed, with no record yet, blocks`() =
        runTest {
            val blocked =
                assertFailsWith<UnfinishedConversionsException> {
                    gate(spending = PrivateUsdSpendStatus.CONVERTING)()
                }

            assertEquals(1, blocked.count)
        }

    @Test
    fun `blocks a refund still to recover, whichever of a kept history it is`() =
        runTest {
            val refunds = mockk<ReverseSwapRepository>()
            coEvery { refunds.canRescue(any()) } returns false
            coEvery { refunds.canRescue(1) } returns true

            val blocked =
                assertFailsWith<UnfinishedConversionsException> {
                    gate(
                        history =
                            listOf(
                                toZec(index = 0, ReversePhase.COMPLETE),
                                toZec(index = 1, ReversePhase.REFUNDED),
                                toZec(index = 2, ReversePhase.REFUNDED),
                            ),
                        refunds = refunds,
                    )()
                }

            assertEquals(0, blocked.count)
            assertTrue(blocked.hasRefund)
        }

    @Test
    fun `passes once a refund has been recovered, without asking about a conversion with none`() =
        runTest {
            val refunds = mockk<ReverseSwapRepository>()
            coEvery { refunds.canRescue(1) } returns false

            gate(
                history = listOf(toZec(index = 0, ReversePhase.COMPLETE), toZec(index = 1, ReversePhase.REFUNDED)),
                refunds = refunds,
            )()

            coVerify(exactly = 0) { refunds.canRescue(0) }
        }

    @Test
    fun `a refund check that fails blocks as unreadable`() =
        runTest {
            val refunds = mockk<ReverseSwapRepository>()
            coEvery { refunds.canRescue(any()) } throws IllegalStateException("offline")

            val blocked =
                assertFailsWith<UnfinishedConversionsException> {
                    gate(history = listOf(toZec(index = 1, ReversePhase.REFUNDED)), refunds = refunds)()
                }

            assertNull(blocked.count)
        }

    @Test
    fun `a refund check that hangs blocks as unreadable rather than hanging the wipe`() =
        runTest {
            val refunds = mockk<ReverseSwapRepository>()
            coEvery { refunds.canRescue(any()) } coAnswers { awaitCancellation() }

            val blocked =
                assertFailsWith<UnfinishedConversionsException> {
                    gate(history = listOf(toZec(index = 1, ReversePhase.REFUNDED)), refunds = refunds)()
                }

            assertNull(blocked.count)
        }

    @Test
    fun `an unreadable history blocks`() =
        runTest {
            val gate =
                EnsureNoUnfinishedConversionsUseCase(
                    forward = mockk { coEvery { active() } returns null },
                    reverse =
                        mockk {
                            coEvery { active() } returns null
                            every { observeHistory } returns flow { throw IllegalStateException("corrupt") }
                        },
                    refunds = mockk(),
                    deposits = mockk { coEvery { records() } returns emptyList() },
                    sends = mockk { coEvery { unsettledCount() } returns 0 },
                    spendGuard = guard(),
                    clock = FIXED_CLOCK,
                )

            val blocked = assertFailsWith<UnfinishedConversionsException> { gate() }

            assertNull(blocked.count)
        }

    private fun gate(
        forward: AtomicSwapRecord? = null,
        reverse: ReverseSwapRecord? = null,
        history: List<ReverseSwapRecord> = emptyList(),
        refunds: ReverseSwapRepository = mockk(),
        deposits: List<DepositRecord> = emptyList(),
        unsettledSends: Int = 0,
        spending: PrivateUsdSpendStatus = PrivateUsdSpendStatus.AVAILABLE,
    ) = EnsureNoUnfinishedConversionsUseCase(
        forward = mockk<AtomicSwapRecords> { coEvery { active() } returns forward },
        reverse =
            mockk<ReverseSwapRecords> {
                coEvery { active() } returns reverse
                every { observeHistory } returns flowOf(history)
            },
        refunds = refunds,
        deposits = mockk<PrivateUsdDepositStore> { coEvery { records() } returns deposits },
        sends = mockk<PrivateUsdSendLog> { coEvery { unsettledCount() } returns unsettledSends },
        spendGuard = guard(spending),
        clock = FIXED_CLOCK,
    )

    private fun guard(spending: PrivateUsdSpendStatus = PrivateUsdSpendStatus.AVAILABLE) =
        mockk<PrivateUsdSpendGuard> { every { state } returns MutableStateFlow(spending) }

    private companion object {
        const val NOW = 1_792_800_000L

        val FIXED_CLOCK =
            object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(NOW)
            }
    }
}
