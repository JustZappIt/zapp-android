package co.electriccoin.zcash.ui.screen.swap.upi.progress

import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.provider.BridgeTerminallyFailedException
import co.electriccoin.zcash.ui.common.provider.OfframpCheckpointStorageProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.orchestrator.OfframpCheckpoint
import xyz.justzappit.offramp.orchestrator.OfframpRequest
import xyz.justzappit.offramp.orchestrator.OfframpStatus
import xyz.justzappit.offramp.orchestrator.OfframpStep
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfframpCheckpointPersisterTest {
    @Test
    fun `placeOrderTxHash is captured at PlacingOrder and survives to the WaitingForAcceptance save`() =
        runBlocking {
            // Regression for the bug where persister never recorded PlacingOrder.txHash:
            // by the time WaitingForMerchantAcceptance fired (which carries orderId and therefore
            // triggers persist), the orchestrator had already moved past PlacingOrder, so
            // `(status as? PlacingOrder)?.txHash` was always null, leaving the checkpoint's
            // placeOrderTxHash forever null and breaking the post-resume UI render.
            val repo = InMemoryCheckpointStorage()
            val persister = OfframpCheckpointPersister(repo, freshRequest())

            persister.onStatus(OfframpStatus.Idle)
            persister.onStatus(OfframpStatus.ApprovingUsdc(txHash = APPROVE_HASH, amount = AMOUNT))
            persister.onStatus(
                OfframpStatus.PlacingOrder(txHash = PLACE_ORDER_HASH, circleId = BigInteger.ONE, amount = AMOUNT),
            )
            persister.onStatus(OfframpStatus.WaitingForMerchantAcceptance(orderId = ORDER_ID))

            val saved = repo.get()!!
            assertEquals(ORDER_ID.toString(), saved.orderId)
            assertEquals(APPROVE_HASH, saved.approveTxHash)
            assertEquals(PLACE_ORDER_HASH, saved.placeOrderTxHash) // ← would have been null before the fix
            assertEquals(FIAT_AMOUNT_LIMIT.micros.toString(), saved.fiatAmountLimitMicroDecimal)
            assertNull(saved.setUpiTxHash)
        }

    @Test
    fun `setUpiTxHash lands when SendingEncryptedUpi fires`() =
        runBlocking {
            val repo = InMemoryCheckpointStorage()
            val persister = OfframpCheckpointPersister(repo, freshRequest())

            persister.onStatus(OfframpStatus.ApprovingUsdc(txHash = APPROVE_HASH, amount = AMOUNT))
            persister.onStatus(
                OfframpStatus.PlacingOrder(txHash = PLACE_ORDER_HASH, circleId = BigInteger.ONE, amount = AMOUNT),
            )
            persister.onStatus(OfframpStatus.WaitingForMerchantAcceptance(orderId = ORDER_ID))
            persister.onStatus(
                OfframpStatus.SendingEncryptedUpi(
                    orderId = ORDER_ID,
                    txHash = SET_UPI_HASH,
                    merchantAddress = MERCHANT,
                    merchantPubKey = "pubkey",
                    paymentAddress = "merchant@upi",
                ),
            )

            val saved = repo.get()!!
            assertEquals(APPROVE_HASH, saved.approveTxHash)
            assertEquals(PLACE_ORDER_HASH, saved.placeOrderTxHash)
            assertEquals(SET_UPI_HASH, saved.setUpiTxHash)
        }

    @Test
    fun `Completed status clears the checkpoint`() =
        runBlocking {
            val repo = InMemoryCheckpointStorage()
            repo.store(seededCheckpoint())
            val persister = OfframpCheckpointPersister(repo, freshRequest())

            persister.onStatus(OfframpStatus.Completed(orderId = ORDER_ID, acceptedMerchant = MERCHANT))

            assertNull(repo.get())
        }

    @Test
    fun `Cancelled status clears the checkpoint`() =
        runBlocking {
            val repo = InMemoryCheckpointStorage()
            repo.store(seededCheckpoint())
            val persister = OfframpCheckpointPersister(repo, freshRequest())

            persister.onStatus(
                OfframpStatus.Cancelled(
                    orderId = ORDER_ID,
                    cancelledAtEpochSeconds = 1_779_500_000L,
                    refundedUsdcAmount = AMOUNT,
                ),
            )

            assertNull(repo.get())
        }

    @Test
    fun `Failed past the bridge step clears the checkpoint`() =
        runBlocking {
            // Bridge has long since settled; the USDC is parked in the smart account, so a fresh attempt
            // will FundedFromBase. No reason to keep the checkpoint around for resume.
            val repo = InMemoryCheckpointStorage()
            repo.store(seededCheckpoint())
            val persister = OfframpCheckpointPersister(repo, freshRequest())

            persister.onStatus(
                OfframpStatus.Failed(
                    message = "kapow",
                    orderId = ORDER_ID,
                    step = OfframpStep.WAITING_FOR_ACCEPTANCE,
                ),
            )

            assertNull(repo.get())
        }

    @Test
    fun `Failed during FUNDING with an open bridge keeps the checkpoint for resume`() =
        runBlocking {
            // The user's ZEC may be mid-bridge on 1-Click; the bridge runs server-side regardless of
            // our orchestrator state. Clearing here would orphan the in-flight ZEC with no resume path.
            // The persisted bridgeDepositAddress is the idempotency key for re-polling on resume.
            val repo = InMemoryCheckpointStorage()
            val persister = OfframpCheckpointPersister(repo, freshRequest())

            persister.onStatus(OfframpStatus.BridgingFunds(amount = AMOUNT, depositAddress = "near-deposit-abc"))
            persister.onStatus(
                OfframpStatus.Failed(
                    message = "1Click status poll failed",
                    orderId = null,
                    step = OfframpStep.FUNDING,
                ),
            )

            val saved = repo.get()!!
            assertNull(saved.orderId)
            assertEquals("near-deposit-abc", saved.bridgeDepositAddress)
            assertEquals(OfframpStep.FUNDING, saved.currentStep)
        }

    @Test
    fun `Failed during FUNDING with a terminally-dead bridge clears the checkpoint`() =
        runBlocking {
            // 1-Click reported a terminal status (REFUNDED/FAILED/EXPIRED/INCOMPLETE_DEPOSIT) for the
            // bridge — re-polling the same deposit address would just yield the same terminal status
            // forever and loop the user with no UX exit. The structural signal is the typed
            // BridgeTerminallyFailedException as Failed.cause. Without this, the persister would
            // keep the checkpoint (since lastBridgeDepositAddress != null) and the user would be
            // stuck on a dead resume forever.
            val repo = InMemoryCheckpointStorage()
            val persister = OfframpCheckpointPersister(repo, freshRequest())

            persister.onStatus(OfframpStatus.BridgingFunds(amount = AMOUNT, depositAddress = "near-deposit-abc"))
            persister.onStatus(
                OfframpStatus.Failed(
                    message = "bridge refunded",
                    orderId = null,
                    step = OfframpStep.FUNDING,
                    cause =
                        BridgeTerminallyFailedException(
                            terminalStatus = SwapStatus.REFUNDED,
                        ),
                ),
            )

            assertNull(repo.get())
        }

    @Test
    fun `Failed during FUNDING with no bridge open clears the checkpoint`() =
        runBlocking {
            // Failure before BridgingFunds fired means no ZEC has moved (quote fetch failed or similar).
            // Nothing to resume — clear so the user starts fresh on the next attempt.
            val repo = InMemoryCheckpointStorage()
            val persister = OfframpCheckpointPersister(repo, freshRequest())

            persister.onStatus(OfframpStatus.Idle)
            persister.onStatus(
                OfframpStatus.Failed(
                    message = "quote unavailable",
                    orderId = null,
                    step = OfframpStep.FUNDING,
                ),
            )

            assertNull(repo.get())
        }

    @Test
    fun `seedFrom hydrates the in-memory cache so a resume-only flow preserves earlier hashes`() =
        runBlocking {
            // Simulate: original VM lifecycle captured APPROVE_HASH + PLACE_ORDER_HASH and was
            // persisted. The new VM resumes from the checkpoint. The orchestrator's resume() does
            // NOT re-emit ApprovingUsdc / PlacingOrder, so without seedFrom() the persister's
            // in-memory cache would be empty and the next save would clobber both hashes with null.
            val repo = InMemoryCheckpointStorage()
            val seeded = seededCheckpoint()
            repo.store(seeded)
            val persister = OfframpCheckpointPersister(repo, freshRequest())
            persister.seedFrom(seeded)

            persister.onStatus(
                OfframpStatus.SendingEncryptedUpi(
                    orderId = ORDER_ID,
                    txHash = SET_UPI_HASH,
                    merchantAddress = MERCHANT,
                    merchantPubKey = "pubkey",
                    paymentAddress = "merchant@upi",
                ),
            )

            val saved = repo.get()!!
            assertEquals(APPROVE_HASH, saved.approveTxHash)
            assertEquals(PLACE_ORDER_HASH, saved.placeOrderTxHash)
            assertEquals(SET_UPI_HASH, saved.setUpiTxHash)
        }

    @Test
    fun `a broadcast placeOrder is checkpointed before its order id is known`() =
        runBlocking {
            // ApprovingUsdc moves no escrow, so it must not produce a half-formed checkpoint. Once
            // placeOrder is broadcast the USDC can already be escrowed, so the submission has to
            // survive process death with a null orderId: resume resolves it by exact identity
            // rather than placing a second order.
            val repo = InMemoryCheckpointStorage()
            val persister = OfframpCheckpointPersister(repo, freshRequest())

            persister.onStatus(OfframpStatus.ApprovingUsdc(txHash = APPROVE_HASH, amount = AMOUNT))
            assertNull(repo.get())

            persister.onStatus(
                OfframpStatus.PlacingOrder(txHash = PLACE_ORDER_HASH, circleId = BigInteger.ONE, amount = AMOUNT),
            )

            val saved = repo.get()!!
            assertNull(saved.orderId)
            assertEquals(PLACE_ORDER_HASH, saved.placeOrderTxHash)
        }

    @Test
    fun `BridgingFunds persists the deposit address for resume (pre-order, no double-bridge)`() =
        runBlocking {
            // The mainnet bridge emits its 1-Click deposit address before any ZEC moves; the persister
            // must checkpoint it (with a null orderId) so a crash mid-bridge resumes by re-polling that
            // address instead of opening a second bridge — the idempotency the orchestrator relies on.
            val repo = InMemoryCheckpointStorage()
            val persister = OfframpCheckpointPersister(repo, freshRequest())

            persister.onStatus(OfframpStatus.Idle)
            persister.onStatus(OfframpStatus.BridgingFunds(amount = AMOUNT, depositAddress = "near-deposit-abc"))

            val saved = repo.get()!!
            assertNull(saved.orderId)
            assertEquals("near-deposit-abc", saved.bridgeDepositAddress)
            assertEquals(OfframpStep.FUNDING, saved.currentStep)
        }

    @Test
    fun `bridge deposit address survives later saves once the order is placed`() =
        runBlocking {
            val repo = InMemoryCheckpointStorage()
            val persister = OfframpCheckpointPersister(repo, freshRequest())

            persister.onStatus(OfframpStatus.BridgingFunds(amount = AMOUNT, depositAddress = "near-deposit-abc"))
            persister.onStatus(OfframpStatus.ApprovingUsdc(txHash = APPROVE_HASH, amount = AMOUNT))
            persister.onStatus(
                OfframpStatus.PlacingOrder(txHash = PLACE_ORDER_HASH, circleId = BigInteger.ONE, amount = AMOUNT),
            )
            persister.onStatus(OfframpStatus.WaitingForMerchantAcceptance(orderId = ORDER_ID))

            val saved = repo.get()!!
            assertEquals(ORDER_ID.toString(), saved.orderId)
            assertEquals("near-deposit-abc", saved.bridgeDepositAddress)
        }

    private fun freshRequest() =
        OfframpRequest(
            recipientUpi = "merchant@upi",
            usdcAmount = AMOUNT,
            fiatAmount = FIAT_AMOUNT,
            fiatAmountLimit = FIAT_AMOUNT_LIMIT,
            currency = CurrencyCode.Inr,
        )

    private fun seededCheckpoint() =
        OfframpCheckpoint(
            orderId = ORDER_ID.toString(),
            currentStep = OfframpStep.WAITING_FOR_ACCEPTANCE,
            approveTxHash = APPROVE_HASH,
            placeOrderTxHash = PLACE_ORDER_HASH,
            setUpiTxHash = null,
            recipientUpi = "merchant@upi",
            usdcAmountMicroDecimal = AMOUNT.micros.toString(),
            fiatAmountMicroDecimal = FIAT_AMOUNT.micros.toString(),
            fiatAmountLimitMicroDecimal = FIAT_AMOUNT_LIMIT.micros.toString(),
            currency = CurrencyCode.Inr,
            createdAtMillis = 1L,
        )

    private class InMemoryCheckpointStorage : OfframpCheckpointStorageProvider {
        private val state = MutableStateFlow<OfframpCheckpoint?>(null)

        override fun observe() = state

        override suspend fun get(): OfframpCheckpoint? = state.value

        override suspend fun store(checkpoint: OfframpCheckpoint) {
            state.update { checkpoint }
        }

        override suspend fun clear() {
            state.update { null }
        }
    }

    companion object {
        private val AMOUNT = Usdc6.ofMicros(5_000_000)
        private val FIAT_AMOUNT = Usdc6.ofMicros(445_000_000)
        private val FIAT_AMOUNT_LIMIT = Usdc6.ofMicros(444_000_000)
        private val ORDER_ID: BigInteger = BigInteger.valueOf(42)
        private val MERCHANT = Address.parse("0x1111111111111111111111111111111111111111")
        private val APPROVE_HASH = TxHash.fromHex("0x" + "aa".repeat(32))
        private val PLACE_ORDER_HASH = TxHash.fromHex("0x" + "bb".repeat(32))
        private val SET_UPI_HASH = TxHash.fromHex("0x" + "cc".repeat(32))
    }
}
