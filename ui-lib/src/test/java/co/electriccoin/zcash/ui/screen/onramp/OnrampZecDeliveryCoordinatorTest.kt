// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import co.electriccoin.zcash.ui.common.provider.FakeOnrampCheckpointStorage
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.onramp.FundsLocation
import xyz.justzappit.offramp.onramp.OnrampCheckpoint
import xyz.justzappit.offramp.onramp.OnrampDestination
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpoint
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryDriver
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryPhase
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnrampZecDeliveryCoordinatorTest {
    @Test
    fun `repeated completion starts delivery once`() =
        runTest {
            val driver = RecordingDriver()
            val coordinator = OnrampZecDeliveryCoordinator(driver, storage(checkpoint()), this) {}

            coordinator.start(ORDER_ID, ACCOUNT, AMOUNT, checkpoint().zecDelivery)
            advanceUntilIdle()
            coordinator.start(ORDER_ID, ACCOUNT, AMOUNT, checkpoint().zecDelivery)
            advanceUntilIdle()

            assertEquals(1, driver.calls)
        }

    @Test
    fun `retry after confirmed refund starts from funds on Base`() =
        runTest {
            val storage = storage(checkpoint(OnrampZecDeliveryPhase.REFUNDED_TO_BASE))
            val driver = RecordingDriver()
            val coordinator = OnrampZecDeliveryCoordinator(driver, storage, this) {}

            coordinator.retry()
            advanceUntilIdle()

            assertEquals(OnrampZecDeliveryPhase.FUNDS_ON_BASE, driver.resume?.phase)
            assertEquals(REFUNDED_AMOUNT.micros.toString(), driver.resume?.usdcMicros)
            assertEquals(OnrampZecDeliveryPhase.FUNDS_ON_BASE, storage.get()?.zecDelivery?.phase)
        }

    @Test
    fun `unexpected failure uses latest durable transfer state`() =
        runTest {
            val resume = checkpoint().zecDelivery
            val storage = storage(checkpoint(OnrampZecDeliveryPhase.TRANSFER_STARTING))
            val statuses = mutableListOf<OnrampZecDeliveryStatus>()
            val coordinator = OnrampZecDeliveryCoordinator(FailingDriver(), storage, this, statuses::add)

            coordinator.start(ORDER_ID, ACCOUNT, AMOUNT, resume)
            advanceUntilIdle()

            val failure = statuses.single() as OnrampZecDeliveryStatus.Failed
            assertEquals(OnrampZecDeliveryPhase.TRANSFER_STARTING, failure.stage)
            assertEquals(FundsLocation.TRANSFER_AMBIGUOUS, failure.fundsLocation)
            assertEquals(false, failure.retryable)
        }

    @Test
    fun `a checkpoint this build cannot decode does not take the screen down`() =
        runTest {
            val storage = FakeOnrampCheckpointStorage(readFailure = StoreCorruptedException("undecodable"))
            val statuses = mutableListOf<OnrampZecDeliveryStatus>()
            val coordinator = OnrampZecDeliveryCoordinator(RecordingDriver(), storage, this, statuses::add)

            coordinator.retry()
            advanceUntilIdle()

            assertTrue(statuses.isEmpty())
        }

    @Test
    fun `an unreadable checkpoint still yields a fail-closed status after a driver crash`() =
        runTest {
            val storage = FakeOnrampCheckpointStorage(readFailure = StoreCorruptedException("undecodable"))
            val statuses = mutableListOf<OnrampZecDeliveryStatus>()
            val coordinator = OnrampZecDeliveryCoordinator(FailingDriver(), storage, this, statuses::add)

            coordinator.start(ORDER_ID, ACCOUNT, AMOUNT, checkpoint().zecDelivery)
            advanceUntilIdle()

            val failure = statuses.single() as OnrampZecDeliveryStatus.Failed
            assertEquals(FundsLocation.TRANSFER_AMBIGUOUS, failure.fundsLocation)
            assertEquals(false, failure.retryable)
        }

    private class RecordingDriver : OnrampZecDeliveryDriver {
        var calls = 0
        var resume: OnrampZecDeliveryCheckpoint? = null

        override fun deliver(
            orderId: String,
            recipient: Address,
            amount: Usdc6,
            resume: OnrampZecDeliveryCheckpoint?,
        ): Flow<OnrampZecDeliveryStatus> {
            calls++
            this.resume = resume
            return flowOf(OnrampZecDeliveryStatus.Preparing(amount))
        }
    }

    private class FailingDriver : OnrampZecDeliveryDriver {
        override fun deliver(
            orderId: String,
            recipient: Address,
            amount: Usdc6,
            resume: OnrampZecDeliveryCheckpoint?,
        ): Flow<OnrampZecDeliveryStatus> = flow { error("unexpected delivery failure") }
    }

    private companion object {
        const val ORDER_ID = "onramp-order"
        val ACCOUNT: Address = Address.parse("0x0000000000000000000000000000000000000001")
        val AMOUNT: Usdc6 = Usdc6.ofMicros(910_153)

        fun storage(initial: OnrampCheckpoint) = FakeOnrampCheckpointStorage(initial)

        fun checkpoint(phase: OnrampZecDeliveryPhase = OnrampZecDeliveryPhase.FUNDS_ON_BASE) =
            OnrampCheckpoint(
                id = ORDER_ID,
                phase = OnrampPhase.COMPLETED,
                destination = OnrampDestination.ZCASH,
                zecDelivery =
                    OnrampZecDeliveryCheckpoint(
                        phase = phase,
                        usdcMicros = AMOUNT.micros.toString(),
                        baseAccount = ACCOUNT.checksumHex,
                        zcashRecipient = ZCASH_RECIPIENT.takeIf { phase.hasQuote },
                        depositAddress = DEPOSIT.takeIf { phase.hasQuote },
                        quoteDeadlineMillis = DEADLINE.takeIf { phase.hasQuote },
                        transferStarted = phase.hasQuote,
                        userOperationHash = USER_OPERATION_HASH.takeIf { phase.hasQuote },
                        baseTransactionHash =
                            BASE_TRANSACTION_HASH.takeIf {
                                phase == OnrampZecDeliveryPhase.REFUNDED_TO_BASE
                            },
                        refundedUsdcMicros =
                            REFUNDED_AMOUNT.micros.toString().takeIf {
                                phase == OnrampZecDeliveryPhase.REFUNDED_TO_BASE
                            },
                    ),
            )

        val OnrampZecDeliveryPhase.hasQuote: Boolean
            get() = this == OnrampZecDeliveryPhase.TRANSFER_STARTING || this == OnrampZecDeliveryPhase.REFUNDED_TO_BASE

        const val ZCASH_RECIPIENT = "u1test-recipient"
        const val DEPOSIT = "0x0000000000000000000000000000000000000002"
        const val DEADLINE = 1_900_000_000_000L
        const val USER_OPERATION_HASH = "0xuser-operation"
        const val BASE_TRANSACTION_HASH = "0xbase-transaction"
        val REFUNDED_AMOUNT: Usdc6 = Usdc6.ofMicros(900_000)
    }
}
