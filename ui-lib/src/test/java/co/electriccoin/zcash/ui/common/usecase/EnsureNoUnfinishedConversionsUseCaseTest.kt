// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRecords
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRecords
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositStore
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendLog
import co.electriccoin.zcash.ui.common.privateusd.depositRecord
import co.electriccoin.zcash.ui.screen.privateusd.toUsd
import co.electriccoin.zcash.ui.screen.privateusd.toZec
import io.mockk.coEvery
import io.mockk.mockk
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
                    deposits = mockk { coEvery { records() } returns emptyList() },
                    sends = mockk { coEvery { unsettledCount() } returns 0 },
                    clock = FIXED_CLOCK,
                )

            val blocked = assertFailsWith<UnfinishedConversionsException> { gate() }

            assertNull(blocked.count)
        }

    private fun gate(
        forward: AtomicSwapRecord? = null,
        reverse: ReverseSwapRecord? = null,
        deposits: List<DepositRecord> = emptyList(),
        unsettledSends: Int = 0,
    ) = EnsureNoUnfinishedConversionsUseCase(
        forward = mockk<AtomicSwapRecords> { coEvery { active() } returns forward },
        reverse = mockk<ReverseSwapRecords> { coEvery { active() } returns reverse },
        deposits = mockk<PrivateUsdDepositStore> { coEvery { records() } returns deposits },
        sends = mockk<PrivateUsdSendLog> { coEvery { unsettledCount() } returns unsettledSends },
        clock = FIXED_CLOCK,
    )

    private companion object {
        const val NOW = 1_792_800_000L

        val FIXED_CLOCK =
            object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(NOW)
            }
    }
}
