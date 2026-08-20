// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.signer.TxSubmitter
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.offramp.account.SubmittingAccount
import xyz.justzappit.offramp.onramp.Erc4337OnrampZecTransferGateway
import xyz.justzappit.offramp.onramp.FundsLocation
import xyz.justzappit.offramp.onramp.NearOnrampZecDeliveryDriver
import xyz.justzappit.offramp.onramp.OnrampBaseTransferReceipt
import xyz.justzappit.offramp.onramp.OnrampUsdcBalanceReader
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpoint
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpointStore
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryPhase
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus
import xyz.justzappit.offramp.onramp.OnrampZecSwapGateway
import xyz.justzappit.offramp.onramp.OnrampZecSwapResult
import xyz.justzappit.offramp.onramp.OnrampZecTransferGateway
import xyz.justzappit.offramp.onramp.SwapStatus
import xyz.justzappit.offramp.onramp.ValidatedZecSwapQuote
import xyz.justzappit.offramp.p2p.Erc20Calls
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NearOnrampZecDeliveryDriverTest {
    @Test
    fun `fresh delivery transfers the exact order amount after durable checkpoints`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(success())))
            val store = RecordingStore()

            val statuses = driver(transfer, swap, store).deliver(ORDER_ID, ACCOUNT, AMOUNT).toList()

            assertEquals(AMOUNT, transfer.submittedAmount)
            assertEquals(DEPOSIT, transfer.submittedDeposit)
            assertEquals(
                listOf(
                    OnrampZecDeliveryPhase.QUOTING,
                    OnrampZecDeliveryPhase.QUOTE_READY,
                    OnrampZecDeliveryPhase.TRANSFER_STARTING,
                    OnrampZecDeliveryPhase.TRANSFER_SUBMITTED,
                    OnrampZecDeliveryPhase.AWAITING_ZEC,
                    OnrampZecDeliveryPhase.DELIVERED,
                ),
                store.saved.map { it.phase },
            )
            assertIs<OnrampZecDeliveryStatus.Preparing>(statuses[0])
            assertIs<OnrampZecDeliveryStatus.Submitting>(statuses[1])
            assertIs<OnrampZecDeliveryStatus.AwaitingZec>(statuses[2])
            assertEquals(OUTPUT_ZEC, assertIs<OnrampZecDeliveryStatus.Delivered>(statuses[3]).outputZec)
        }

    @Test
    fun `insufficient Base balance never quotes or transfers`() =
        runTest {
            val transfer = RecordingTransfer(balance = Usdc6.ofMicros(AMOUNT.micros.toLong() - 1))
            val swap = RecordingSwap()
            val store = RecordingStore()

            val statuses = driver(transfer, swap, store).deliver(ORDER_ID, ACCOUNT, AMOUNT).toList()

            val failed = assertIs<OnrampZecDeliveryStatus.Failed>(statuses.last())
            assertEquals(FundsLocation.BASE_ACCOUNT, failed.fundsLocation)
            assertTrue(failed.retryable)
            assertEquals(0, swap.quoteCalls)
            assertEquals(0, transfer.submitCalls)
        }

    @Test
    fun `recipient mismatch fails before balance or quote access`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap()

            val failed =
                assertIs<OnrampZecDeliveryStatus.Failed>(
                    driver(transfer, swap).deliver(ORDER_ID, OTHER_ACCOUNT, AMOUNT).toList().single()
                )

            assertEquals(FundsLocation.RECIPIENT_MISMATCH, failed.fundsLocation)
            assertEquals(0, transfer.balanceCalls)
            assertEquals(0, swap.quoteCalls)
        }

    @Test
    fun `checkpoint recipient mismatch is not reported as custody in the current Base account`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val resume =
                checkpoint(OnrampZecDeliveryPhase.QUOTE_READY).copy(
                    baseAccount = OTHER_ACCOUNT.checksumHex,
                )

            val failed =
                assertIs<OnrampZecDeliveryStatus.Failed>(
                    driver(transfer, RecordingSwap())
                        .deliver(ORDER_ID, ACCOUNT, AMOUNT, resume)
                        .toList()
                        .single()
                )

            assertEquals(FundsLocation.RECIPIENT_MISMATCH, failed.fundsLocation)
            assertEquals(0, transfer.resolveCalls)
        }

    @Test
    fun `a checkpoint for another amount is never delivered against this order`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap()
            val resume = checkpoint(OnrampZecDeliveryPhase.QUOTE_READY).copy(usdcMicros = "1")

            val failed =
                assertIs<OnrampZecDeliveryStatus.Failed>(
                    driver(transfer, swap).deliver(ORDER_ID, ACCOUNT, AMOUNT, resume).toList().single()
                )

            assertEquals(FundsLocation.BASE_ACCOUNT, failed.fundsLocation)
            assertEquals(0, transfer.submitCalls)
            assertEquals(0, swap.quoteCalls)
        }

    @Test
    fun `settling for a different amount leaves a retryable checkpoint for the amount that arrived`() =
        runTest {
            val transfer = RecordingTransfer(balance = SETTLED)
            val swap = RecordingSwap()
            val store = RecordingStore()
            val resume = checkpoint(OnrampZecDeliveryPhase.QUOTE_READY)

            val failed =
                assertIs<OnrampZecDeliveryStatus.Failed>(
                    driver(transfer, swap, store).deliver(ORDER_ID, ACCOUNT, SETTLED, resume).toList().single()
                )

            assertEquals(FundsLocation.BASE_ACCOUNT, failed.fundsLocation)
            assertTrue(failed.retryable, "an unstarted transfer leaves the USDC on Base, so it can be re-quoted")
            assertEquals(0, transfer.submitCalls)
            assertEquals(0, swap.quoteCalls)
            val rewritten = store.saved.single()
            assertEquals(OnrampZecDeliveryPhase.FUNDS_ON_BASE, rewritten.phase)
            assertEquals(SETTLED.micros.toString(), rewritten.usdcMicros)
        }

    @Test
    fun `retrying after a settlement disagreement re-quotes instead of rediscovering it`() =
        runTest {
            val transfer = RecordingTransfer(balance = SETTLED)
            val swap = RecordingSwap(results = ArrayDeque(listOf(success())))
            val store = RecordingStore()
            driver(transfer, swap, store).deliver(ORDER_ID, ACCOUNT, SETTLED, checkpoint(QUOTE_READY)).toList()

            val delivered =
                driver(transfer, swap, store)
                    .deliver(ORDER_ID, ACCOUNT, SETTLED, store.saved.single())
                    .toList()
                    .last()

            assertIs<OnrampZecDeliveryStatus.Delivered>(delivered)
            assertEquals(1, swap.quoteCalls)
            assertEquals(SETTLED, transfer.submittedAmount)
        }

    @Test
    fun `a settlement disagreement after the transfer started stays non-retryable`() =
        runTest {
            val store = RecordingStore()

            val failed =
                assertIs<OnrampZecDeliveryStatus.Failed>(
                    driver(RecordingTransfer(balance = SETTLED), RecordingSwap(), store)
                        .deliver(ORDER_ID, ACCOUNT, SETTLED, checkpoint(OnrampZecDeliveryPhase.TRANSFER_SUBMITTED))
                        .toList()
                        .single()
                )

            assertEquals(FundsLocation.TRANSFER_AMBIGUOUS, failed.fundsLocation)
            assertTrue(store.saved.isEmpty(), "an in-flight transfer's checkpoint must not be rewritten")
        }

    @Test
    fun `a settlement re-quote costing more than the accepted estimate is never deposited`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(inputUsd = "1.00", outputUsd = "0.50")
            val store = RecordingStore()
            val resume = checkpoint(OnrampZecDeliveryPhase.FUNDS_ON_BASE).copy(acceptedCostBps = 300)

            val failed =
                assertIs<OnrampZecDeliveryStatus.Failed>(
                    driver(transfer, swap, store).deliver(ORDER_ID, ACCOUNT, AMOUNT, resume).toList().last()
                )

            assertEquals(FundsLocation.BASE_ACCOUNT, failed.fundsLocation)
            assertTrue(failed.retryable)
            assertEquals(0, transfer.submitCalls)
            assertEquals(OnrampZecDeliveryPhase.FUNDS_ON_BASE, store.saved.last().phase)
            assertEquals(300, store.saved.last().acceptedCostBps, "the accepted cost survives for the next retry")
        }

    @Test
    fun `a settlement re-quote within tolerance of the accepted estimate is delivered`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(success())), inputUsd = "1.00", outputUsd = "0.95")
            val resume = checkpoint(OnrampZecDeliveryPhase.FUNDS_ON_BASE).copy(acceptedCostBps = 300)

            val delivered = driver(transfer, swap).deliver(ORDER_ID, ACCOUNT, AMOUNT, resume).toList().last()

            assertIs<OnrampZecDeliveryStatus.Delivered>(delivered)
            assertEquals(AMOUNT, transfer.submittedAmount)
        }

    @Test
    fun `a checkpoint with no accepted cost is delivered unconditionally`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(success())), inputUsd = "1.00", outputUsd = "0.10")

            val delivered =
                driver(transfer, swap)
                    .deliver(ORDER_ID, ACCOUNT, AMOUNT, checkpoint(OnrampZecDeliveryPhase.FUNDS_ON_BASE))
                    .toList()
                    .last()

            assertIs<OnrampZecDeliveryStatus.Delivered>(delivered)
        }

    @Test
    fun `ambiguous transfer start never submits automatically`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(pending())))
            val store = RecordingStore()

            val failed =
                assertIs<OnrampZecDeliveryStatus.Failed>(
                    driver(transfer, swap, store)
                        .deliver(ORDER_ID, ACCOUNT, AMOUNT, checkpoint(OnrampZecDeliveryPhase.TRANSFER_STARTING))
                        .toList()
                        .last()
                )

            assertEquals(FundsLocation.TRANSFER_AMBIGUOUS, failed.fundsLocation)
            assertEquals(0, transfer.submitCalls)
            assertEquals(OnrampZecDeliveryPhase.NEEDS_ATTENTION, store.saved.single().phase)
        }

    @Test
    fun `manual status check resumes a known NEAR intent without another transfer`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(success())))
            val resume =
                checkpoint(OnrampZecDeliveryPhase.NEEDS_ATTENTION).copy(
                    baseTransactionHash = BASE_TRANSACTION_HASH,
                )

            val delivered = driver(transfer, swap).deliver(ORDER_ID, ACCOUNT, AMOUNT, resume).toList().last()

            assertIs<OnrampZecDeliveryStatus.Delivered>(delivered)
            assertEquals(0, transfer.submitCalls)
            assertEquals(0, swap.quoteCalls)
        }

    @Test
    fun `submitted resume waits for its stored UserOperation without rebuilding transfer`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(success())))

            val delivered =
                driver(transfer, swap)
                    .deliver(ORDER_ID, ACCOUNT, AMOUNT, checkpoint(OnrampZecDeliveryPhase.TRANSFER_SUBMITTED))
                    .toList()
                    .last()

            assertIs<OnrampZecDeliveryStatus.Delivered>(delivered)
            assertEquals(USER_OPERATION_HASH, transfer.awaitedHash)
            assertEquals(0, transfer.submitCalls)
            assertEquals(0, swap.quoteCalls)
        }

    @Test
    fun `a live quote is reused on resume instead of paying for a second one`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(success())))
            val store = RecordingStore()

            driver(transfer, swap, store)
                .deliver(ORDER_ID, ACCOUNT, AMOUNT, checkpoint(OnrampZecDeliveryPhase.QUOTE_READY))
                .toList()

            assertEquals(0, swap.quoteCalls)
            assertEquals(1, transfer.submitCalls)
            assertEquals(DEPOSIT, transfer.submittedDeposit)
            assertEquals(OnrampZecDeliveryPhase.TRANSFER_STARTING, store.saved.first().phase)
        }

    @Test
    fun `an unused quote too close to its deadline is discarded and requoted`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(success())))
            val expiring = checkpoint(OnrampZecDeliveryPhase.QUOTE_READY).copy(quoteDeadlineMillis = NOW + 1_000L)

            driver(transfer, swap).deliver(ORDER_ID, ACCOUNT, AMOUNT, expiring).toList()

            assertEquals(1, swap.quoteCalls)
            assertEquals(1, transfer.submitCalls)
        }

    @Test
    fun `awaiting resume polls the stored deposit and shows the leg is still in flight`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(success())))
            val awaiting =
                checkpoint(OnrampZecDeliveryPhase.AWAITING_ZEC).copy(baseTransactionHash = BASE_TRANSACTION_HASH)

            val statuses = driver(transfer, swap).deliver(ORDER_ID, ACCOUNT, AMOUNT, awaiting).toList()

            assertIs<OnrampZecDeliveryStatus.AwaitingZec>(statuses.first())
            assertIs<OnrampZecDeliveryStatus.Delivered>(statuses.last())
            assertEquals(0, transfer.submitCalls)
            assertEquals(0, swap.quoteCalls)
        }

    @Test
    fun `an acknowledged delivery is replayed from the checkpoint, never re-polled`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap()
            val store = RecordingStore()
            val delivered =
                checkpoint(OnrampZecDeliveryPhase.DELIVERED).copy(
                    baseTransactionHash = BASE_TRANSACTION_HASH,
                    outputZec = OUTPUT_ZEC,
                )

            val status =
                driver(transfer, swap, store).deliver(ORDER_ID, ACCOUNT, AMOUNT, delivered).toList().single()

            val replayed = assertIs<OnrampZecDeliveryStatus.Delivered>(status)
            assertEquals(OUTPUT_ZEC, replayed.outputZec)
            assertEquals(BASE_TRANSACTION_HASH, replayed.baseTransactionHash)
            assertEquals(0, swap.statusCalls)
            assertEquals(0, transfer.resolveCalls)
            assertTrue(store.saved.isEmpty())
        }

    @Test
    fun `an acknowledged refund is replayed from the checkpoint, never re-polled`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap()
            val store = RecordingStore()
            val refunded =
                checkpoint(OnrampZecDeliveryPhase.REFUNDED_TO_BASE).copy(
                    baseTransactionHash = BASE_TRANSACTION_HASH,
                    refundedUsdcMicros = REFUNDED_AMOUNT.micros.toString(),
                )

            val status =
                driver(transfer, swap, store).deliver(ORDER_ID, ACCOUNT, AMOUNT, refunded).toList().single()

            val replayed = assertIs<OnrampZecDeliveryStatus.RefundedToBase>(status)
            assertEquals(REFUNDED_AMOUNT, replayed.refundedUsdc)
            assertEquals(ACCOUNT, replayed.baseAccount)
            assertEquals(0, swap.statusCalls)
            assertTrue(store.saved.isEmpty())
        }

    @Test
    fun `a delivered checkpoint survives a provider that has stopped answering`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap()
            val store = RecordingStore()
            val delivered = checkpoint(OnrampZecDeliveryPhase.DELIVERED).copy(outputZec = OUTPUT_ZEC)

            val status =
                driver(transfer, swap, store).deliver(ORDER_ID, ACCOUNT, AMOUNT, delivered).toList().single()

            assertIs<OnrampZecDeliveryStatus.Delivered>(status)
            assertTrue(store.saved.none { it.phase == OnrampZecDeliveryPhase.NEEDS_ATTENTION })
        }

    @Test
    fun `reverted UserOperation returns to a safely retryable Base checkpoint`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT, receiptSucceeds = false)
            val store = RecordingStore()

            val failed =
                assertIs<OnrampZecDeliveryStatus.Failed>(
                    driver(transfer, RecordingSwap(), store)
                        .deliver(ORDER_ID, ACCOUNT, AMOUNT, checkpoint(OnrampZecDeliveryPhase.TRANSFER_SUBMITTED))
                        .toList()
                        .last()
                )

            assertEquals(FundsLocation.BASE_ACCOUNT, failed.fundsLocation)
            assertTrue(failed.retryable)
            assertEquals(OnrampZecDeliveryPhase.FUNDS_ON_BASE, store.saved.single().phase)
            assertEquals(0, transfer.submitCalls)
        }

    @Test
    fun `notification failure cannot resend the transfer`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(success())), notificationFails = true)

            val delivered = driver(transfer, swap).deliver(ORDER_ID, ACCOUNT, AMOUNT).toList().last()

            assertIs<OnrampZecDeliveryStatus.Delivered>(delivered)
            assertEquals(1, transfer.submitCalls)
            assertEquals(1, swap.notificationCalls)
        }

    @Test
    fun `provider failure after transfer remains a NEAR intent needing attention`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(result(SwapStatus.FAILED))))
            val store = RecordingStore()

            val failed =
                assertIs<OnrampZecDeliveryStatus.Failed>(
                    driver(transfer, swap, store).deliver(ORDER_ID, ACCOUNT, AMOUNT).toList().last()
                )

            assertEquals(FundsLocation.NEAR_INTENT, failed.fundsLocation)
            assertEquals(OnrampZecDeliveryPhase.NEEDS_ATTENTION, store.saved.last().phase)
        }

    @Test
    fun `an expired or partial deposit after transfer keeps its recovery state`() =
        runTest {
            listOf(SwapStatus.EXPIRED, SwapStatus.INCOMPLETE_DEPOSIT).forEach { status ->
                val store = RecordingStore()
                val swap = RecordingSwap(results = ArrayDeque(listOf(result(status))))

                val failed =
                    assertIs<OnrampZecDeliveryStatus.Failed>(
                        driver(RecordingTransfer(balance = AMOUNT), swap, store)
                            .deliver(ORDER_ID, ACCOUNT, AMOUNT)
                            .toList()
                            .last()
                    )

                assertEquals(FundsLocation.NEAR_INTENT, failed.fundsLocation)
                val recovery = store.saved.last()
                assertEquals(OnrampZecDeliveryPhase.NEEDS_ATTENTION, recovery.phase)
                assertEquals(BASE_TRANSACTION_HASH, recovery.baseTransactionHash)
            }
        }

    @Test
    fun `provider-confirmed refund emits the Base refund result and stores its amount`() =
        runTest {
            val transfer = RecordingTransfer(balance = AMOUNT)
            val swap = RecordingSwap(results = ArrayDeque(listOf(result(SwapStatus.REFUNDED))))
            val store = RecordingStore()

            val refunded =
                assertIs<OnrampZecDeliveryStatus.RefundedToBase>(
                    driver(transfer, swap, store).deliver(ORDER_ID, ACCOUNT, AMOUNT).toList().last()
                )

            assertEquals(REFUNDED_AMOUNT, refunded.refundedUsdc)
            assertEquals(ACCOUNT, refunded.baseAccount)
            assertEquals(REFUNDED_AMOUNT.micros.toString(), store.saved.last().refundedUsdcMicros)
        }

    @Test
    fun `a delivered swap stores the output it will replay after a restart`() =
        runTest {
            val store = RecordingStore()

            driver(RecordingTransfer(balance = AMOUNT), RecordingSwap(ArrayDeque(listOf(success()))), store)
                .deliver(ORDER_ID, ACCOUNT, AMOUNT)
                .toList()

            val delivered = store.saved.last()
            assertEquals(OnrampZecDeliveryPhase.DELIVERED, delivered.phase)
            assertEquals(OUTPUT_ZEC, delivered.outputZec)
            assertNull(delivered.refundedUsdcMicros)
        }

    @Test
    fun `ERC-4337 gateway builds an exact USDC transfer call`() =
        runTest {
            val submitter = RecordingSubmitter()
            val gateway =
                Erc4337OnrampZecTransferGateway(
                    usdc = USDC,
                    accountResolver = { SubmittingAccount(ACCOUNT, submitter) },
                    balanceReader = OnrampUsdcBalanceReader { AMOUNT },
                )

            gateway.submit(ACCOUNT, DEPOSIT, AMOUNT)

            assertEquals(USDC, submitter.to)
            assertEquals(Wei.ZERO, submitter.value)
            assertContentEquals(Erc20Calls.transferCalldata(DEPOSIT, AMOUNT), submitter.data)
        }

    private fun driver(
        transfer: RecordingTransfer,
        swap: RecordingSwap,
        store: RecordingStore = RecordingStore(),
    ) =
        NearOnrampZecDeliveryDriver(
            transfer = transfer,
            swap = swap,
            checkpoints = store,
            balancePollIntervalMillis = 0,
            maxBalancePolls = 2,
            statusPollIntervalMillis = 0,
            maxStatusFailures = 2,
            nowMillis = { NOW },
        )

    private fun checkpoint(phase: OnrampZecDeliveryPhase): OnrampZecDeliveryCheckpoint =
        OnrampZecDeliveryCheckpoint(
            phase = phase,
            usdcMicros = AMOUNT.micros.toString(),
            baseAccount = ACCOUNT.checksumHex,
            zcashRecipient = ZCASH_RECIPIENT,
            depositAddress = DEPOSIT.checksumHex,
            quoteDeadlineMillis = DEADLINE,
            transferStarted = phase != OnrampZecDeliveryPhase.QUOTE_READY,
            userOperationHash = USER_OPERATION_HASH.takeIf { phase != OnrampZecDeliveryPhase.QUOTE_READY },
            baseTransactionHash =
                BASE_TRANSACTION_HASH.takeIf {
                    phase == OnrampZecDeliveryPhase.AWAITING_ZEC ||
                        phase == OnrampZecDeliveryPhase.DELIVERED ||
                        phase == OnrampZecDeliveryPhase.REFUNDED_TO_BASE
                },
            outputZec = OUTPUT_ZEC.takeIf { phase == OnrampZecDeliveryPhase.DELIVERED },
            refundedUsdcMicros =
                REFUNDED_AMOUNT.micros.toString().takeIf {
                    phase == OnrampZecDeliveryPhase.REFUNDED_TO_BASE
                },
        )

    private class RecordingStore : OnrampZecDeliveryCheckpointStore {
        val saved = mutableListOf<OnrampZecDeliveryCheckpoint>()

        override suspend fun save(orderId: String, checkpoint: OnrampZecDeliveryCheckpoint) {
            assertEquals(ORDER_ID, orderId)
            saved += checkpoint
        }
    }

    private class RecordingTransfer(
        var balance: Usdc6,
        private val receiptSucceeds: Boolean = true,
    ) : OnrampZecTransferGateway {
        var resolveCalls = 0
        var balanceCalls = 0
        var submitCalls = 0
        var submittedAmount: Usdc6? = null
        var submittedDeposit: Address? = null
        var awaitedHash: String? = null

        override suspend fun resolveAccount(): Address {
            resolveCalls++
            return ACCOUNT
        }

        override suspend fun balance(account: Address): Usdc6 {
            balanceCalls++
            return balance
        }

        override suspend fun submit(account: Address, depositAddress: Address, amount: Usdc6): String {
            submitCalls++
            submittedAmount = amount
            submittedDeposit = depositAddress
            return USER_OPERATION_HASH
        }

        override suspend fun awaitReceipt(account: Address, userOperationHash: String): OnrampBaseTransferReceipt {
            awaitedHash = userOperationHash
            return OnrampBaseTransferReceipt(success = receiptSucceeds, transactionHash = BASE_TRANSACTION_HASH)
        }
    }

    private class RecordingSwap(
        private val results: ArrayDeque<OnrampZecSwapResult> = ArrayDeque(),
        private val notificationFails: Boolean = false,
        private val inputUsd: String = "0.91",
        private val outputUsd: String = "0.88",
    ) : OnrampZecSwapGateway {
        var quoteCalls = 0
        var statusCalls = 0
        var notificationCalls = 0

        override suspend fun quote(account: Address, amount: Usdc6): ValidatedZecSwapQuote {
            quoteCalls++
            return ValidatedZecSwapQuote(
                depositAddress = DEPOSIT,
                zcashRecipient = ZCASH_RECIPIENT,
                deadlineMillis = DEADLINE,
                outputZec = OUTPUT_ZEC,
                inputUsd = java.math.BigDecimal(inputUsd),
                outputUsd = java.math.BigDecimal(outputUsd),
            )
        }

        override suspend fun notifyDeposit(baseTransactionHash: String, depositAddress: Address) {
            notificationCalls++
            if (notificationFails) error("notification failed")
        }

        override suspend fun status(checkpoint: OnrampZecDeliveryCheckpoint): OnrampZecSwapResult {
            statusCalls++
            return results.removeFirst()
        }
    }

    private class RecordingSubmitter : TxSubmitter {
        var to: Address? = null
        var value: Wei? = null
        var data: ByteArray? = null

        override suspend fun sendTransaction(to: Address, value: Wei, data: ByteArray): TxHash {
            this.to = to
            this.value = value
            this.data = data
            return TxHash.fromHex(USER_OPERATION_HASH)
        }

        override suspend fun awaitReceipt(txHash: TxHash): TransactionReceipt =
            TransactionReceipt(BASE_TRANSACTION_HASH, "0x1", "0x1", "0x1")
    }

    private companion object {
        const val ORDER_ID = "659007"
        const val ZCASH_RECIPIENT = "u1test-recipient"
        const val OUTPUT_ZEC = "0.019"
        const val NOW = 1_800_000_000_000L
        const val DEADLINE = NOW + 7_200_000L
        const val USER_OPERATION_HASH = "0x1111111111111111111111111111111111111111111111111111111111111111"
        const val BASE_TRANSACTION_HASH = "0x2222222222222222222222222222222222222222222222222222222222222222"
        val ACCOUNT: Address = Address.parse("0x0000000000000000000000000000000000000001")
        val OTHER_ACCOUNT: Address = Address.parse("0x0000000000000000000000000000000000000002")
        val DEPOSIT: Address = Address.parse("0x0000000000000000000000000000000000000003")
        val USDC: Address = Address.parse("0x0000000000000000000000000000000000000004")
        val AMOUNT: Usdc6 = Usdc6.ofMicros(910_153)
        val SETTLED: Usdc6 = Usdc6.ofMicros(910_000)
        val REFUNDED_AMOUNT: Usdc6 = Usdc6.ofMicros(900_000)
        val QUOTE_READY = OnrampZecDeliveryPhase.QUOTE_READY

        fun success() = result(SwapStatus.SUCCESS)

        fun pending() = result(SwapStatus.PENDING)

        fun result(status: SwapStatus) =
            OnrampZecSwapResult(
                status = status,
                outputZec = OUTPUT_ZEC,
                refundedUsdc = REFUNDED_AMOUNT.takeIf { status == SwapStatus.REFUNDED },
            )
    }
}
