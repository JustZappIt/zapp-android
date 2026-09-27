package co.electriccoin.zcash.ui.common.invest

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.invest.model.AccountBalance
import co.electriccoin.zcash.ui.common.invest.model.AuthenticateRequest
import co.electriccoin.zcash.ui.common.invest.model.AuthenticateResponse
import co.electriccoin.zcash.ui.common.invest.model.BalancesResponse
import co.electriccoin.zcash.ui.common.invest.model.GenerateIntentRequest
import co.electriccoin.zcash.ui.common.invest.model.GenerateIntentResponse
import co.electriccoin.zcash.ui.common.invest.model.GeneratedIntent
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.InvestMarket
import co.electriccoin.zcash.ui.common.invest.model.MarketAsset
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.model.SellIntentRefusedException
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.common.invest.model.SubmitIntentRequest
import co.electriccoin.zcash.ui.common.invest.model.SubmitIntentResponse
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpoint
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellRepositoryImpl
import co.electriccoin.zcash.ui.common.invest.repository.InvestSwapAssetSource
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
import co.electriccoin.zcash.ui.common.model.near.QuoteDetails
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.RecipientType
import co.electriccoin.zcash.ui.common.model.near.RefundType
import co.electriccoin.zcash.ui.common.model.near.SubmitDepositTransactionRequest
import co.electriccoin.zcash.ui.common.model.near.SwapDetails
import co.electriccoin.zcash.ui.common.model.near.SwapStatus
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.provider.OfframpBridgeWallet
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.intents.IntentTransferSigner
import xyz.justzappit.offramp.account.SeedPhraseSource
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Suppress("MaxLineLength")
internal class InvestSellRepositoryImplTest : InvestSellRepositoryTestBase() {
    @Test
    fun `an estimate is a dry quote from the private account to a fresh shielded address`() =
        runTest {
            val estimate = assertIs<SellEstimate.Priced>(repository.estimateSell(nvda, SellAmount.All))

            val request = api.quotes.single()
            assertTrue(request.dry)
            assertEquals(nvda.assetId, request.originAsset)
            assertEquals("nep141:zec.omft.near", request.destinationAsset)
            assertEquals(RefundType.CONFIDENTIAL_INTENTS, request.depositType)
            assertEquals(RefundType.CONFIDENTIAL_INTENTS, request.refundType)
            assertEquals(TEST_ACCOUNT_ID, request.refundTo)
            assertEquals(PAYOUT, request.recipient)
            assertEquals(RecipientType.DESTINATION_CHAIN, request.recipientType)
            assertEquals(BigDecimal("440974000000000000"), request.amount)
            assertEquals(BigDecimal("0.0634"), estimate.zecOut)
            assertEquals(BigDecimal("0.00064000"), estimate.withdrawFeeZec)
            repository.estimateSell(nvda, SellAmount.All)
            assertEquals(1, addressesHandedOut) // one estimate address per session
        }

    @Test
    fun `estimates refuse what can't be sold before quoting`() =
        runTest {
            assertEquals(
                SellEstimate.ExceedsHolding(BigDecimal("0.440974000000000000")),
                repository.estimateSell(nvda, SellAmount.Units(BigDecimal("0.5"))),
            )
            assertEquals(SellEstimate.BelowMinimum(BigDecimal(40)), repository.estimateSell(nvda, SellAmount.Usd(BigDecimal(30))))
            holdings.value = "0"
            assertEquals(SellEstimate.NothingHeld, repository.estimateSell(nvda, SellAmount.All))
            assertTrue(api.quotes.isEmpty())
        }

    @Test
    fun `a small holding can still be sold in full`() =
        runTest {
            holdings.value = "100000000000000000" // 0.1 share, about $22

            assertIs<SellEstimate.Priced>(repository.estimateSell(nvda, SellAmount.All))
        }

    @Test
    fun `no liquidity is a normal estimate`() =
        runTest {
            api.quoteFailure = InvestApiException.NoPrice("cid")

            assertEquals(SellEstimate.NoPrice, repository.estimateSell(nvda, SellAmount.All))
        }

    @Test
    fun `a prepared sale carries the checked intent and the earliest deadline`() =
        runTest {
            val prepared = repository.prepareSell(nvda, SellAmount.All)

            val generate = api.generateRequests.single()
            assertEquals("erc191", generate.standard)
            assertEquals(TEST_ACCOUNT_ID, generate.signerId)
            assertEquals(DEPOSIT, generate.depositAddress)
            assertEquals(api.payload(), prepared.signedMessage)
            assertEquals(BigDecimal("0.440974000000000000"), prepared.units)
            assertEquals(BigDecimal("0.06280000"), prepared.zecOutMin)
            // The intent's deadline is 14:08, a minute of margin before it beats the ten-minute price hold.
            assertEquals(Instant.parse("2026-09-28T14:07:00Z"), prepared.expiresAt)
        }

    @Test
    fun `an intent that isn't the reviewed transfer is never shown`() =
        runTest {
            api.payloadAmount = "440974000000000001"

            val refused = assertFailsWith<SellIntentRefusedException> { repository.prepareSell(nvda, SellAmount.All) }

            assertEquals(IntentTransferSigner.Rejection.UNEXPECTED_INTENTS, refused.rejection)
            assertEquals("gen-cid", refused.correlationId)
        }

    @Test
    fun `a live quote that differs from the request is refused before generating an intent`() =
        runTest {
            listOf<(QuoteRequest) -> QuoteRequest>(
                { it.copy(refundTo = "0x9858effd232b4033e47d90003d41ec34ecaeda94") },
                { it.copy(refundType = RefundType.ORIGIN_CHAIN) },
                { it.copy(depositType = RefundType.INTENTS) },
                { it.copy(recipient = "u1someoneelse") },
                { it.copy(destinationAsset = "nep141:wrap.near") },
            ).forEach { tamper ->
                api.echoTamper = tamper
                assertFailsWith<IllegalArgumentException> { repository.prepareSell(nvda, SellAmount.All) }
            }
            assertTrue(api.generateRequests.isEmpty())
        }

    @Test
    fun `biometrics come first, then the checkpoint, then the submission`() =
        runTest {
            val prepared = repository.prepareSell(nvda, SellAmount.All)

            assertEquals(DEPOSIT, repository.executeSell(prepared))

            coVerify(exactly = 1) { biometrics.requestBiometrics(any()) }
            assertEquals(listOf(DEPOSIT), api.checkpointsAtSubmit)
            val signed = api.submitted.single().signedData
            assertEquals(api.payload(), signed.payload)
            assertEquals("erc191", signed.standard)
            assertTrue(signed.signature.startsWith("secp256k1:"))
            assertEquals(
                prepared.intentDeadline.toEpochMilliseconds(),
                checkpoints.items.value
                    .single()
                    .intentDeadlineMillis
            )
        }

    @Test
    fun `a declined prompt signs and submits nothing`() =
        runTest {
            val prepared = repository.prepareSell(nvda, SellAmount.All)
            coEvery { biometrics.requestBiometrics(any()) } throws BiometricsCancelledException()

            assertFailsWith<BiometricsCancelledException> { repository.executeSell(prepared) }

            assertTrue(api.submitted.isEmpty())
            assertTrue(checkpoints.items.value.isEmpty())
        }

    @Test
    fun `a definite refusal clears the checkpoint, an uncertain answer keeps it`() =
        runTest {
            api.submitFailure = InvestApiException.Api(400, "Invalid signature", "c")
            assertFailsWith<InvestApiException.Api> { repository.executeSell(repository.prepareSell(nvda, SellAmount.All)) }
            assertTrue(checkpoints.items.value.isEmpty())

            api.submitFailure = InvestApiException.Api(400, "Nonce already used", "c")
            assertEquals(DEPOSIT, repository.executeSell(repository.prepareSell(nvda, SellAmount.All)))
            assertEquals(1, checkpoints.items.value.size)

            checkpoints.items.value = emptyList()
            api.submitFailure = InvestApiException.Unreachable(IllegalStateException("timeout"))
            assertEquals(DEPOSIT, repository.executeSell(repository.prepareSell(nvda, SellAmount.All)))
            assertEquals(1, checkpoints.items.value.size)
        }

    @Test
    fun `Tor that didn't start or a rejected partner token clears the checkpoint`() =
        runTest {
            listOf(InvestApiException.TorUnavailable(IllegalStateException("tor")), InvestApiException.Unauthorized("c"))
                .forEach { failure ->
                    api.submitFailure = failure
                    val thrown =
                        assertFailsWith<InvestApiException> { repository.executeSell(repository.prepareSell(nvda, SellAmount.All)) }
                    assertEquals(failure, thrown)
                    assertTrue(checkpoints.items.value.isEmpty())
                }
        }

    @Test
    fun `a submitted sale records the balance before it and the intent hash`() =
        runTest {
            repository.executeSell(repository.prepareSell(nvda, SellAmount.All))

            val checkpoint = checkpoints.items.value.single()
            assertEquals("hash", checkpoint.intentHash)
            assertEquals("440974000000000000", checkpoint.heldBeforeBaseUnits)
            assertEquals("440974000000000000", checkpoint.baseUnits)
        }

    @Test
    fun `a prompt that outlasts the price hold signs nothing`() =
        runTest {
            val prepared = repository.prepareSell(nvda, SellAmount.All)
            // 14:07: past the price hold, still before the intent's own 14:08 deadline the signer would enforce.
            coEvery { biometrics.requestBiometrics(any()) } answers { now += 7.minutes }

            val refused = assertFailsWith<IllegalStateException> { repository.executeSell(prepared) }
            assertTrue(refused !is SellIntentRefusedException)

            assertTrue(api.submitted.isEmpty())
            assertTrue(checkpoints.items.value.isEmpty())
        }

    @Test
    fun `a second sale of the same stock waits for the first`() =
        runTest {
            val first = repository.prepareSell(nvda, SellAmount.Usd(BigDecimal(50)))
            val second = repository.prepareSell(nvda, SellAmount.Usd(BigDecimal(50)))
            repository.executeSell(first)

            assertFailsWith<IllegalStateException> { repository.executeSell(second) }
            assertFailsWith<IllegalStateException> { repository.prepareSell(nvda, SellAmount.All) }
            assertEquals(1, api.submitted.size)
        }

    @Test
    fun `an intent with anything beyond the transfer is refused`() =
        runTest {
            api.extraTransferField = "\"memo\":\"hi\","

            assertFailsWith<SellIntentRefusedException> { repository.prepareSell(nvda, SellAmount.All) }
        }

    @Test
    fun `a holding too small for the fees estimates as below the minimum`() =
        runTest {
            api.quoteFailure = InvestApiException.Api(400, "Amount is too low for bridge, try at least 1000000", "c")
            assertEquals(SellEstimate.BelowMinimum(BigDecimal(40)), repository.estimateSell(nvda, SellAmount.All))

            api.quoteFailure = InvestApiException.Api(400, "tokenIn is not valid", "c")
            assertFailsWith<InvestApiException.Api> { repository.estimateSell(nvda, SellAmount.All) }
        }

    @Test
    fun `a stale price is refreshed, and without one the quote is held to the minimum`() =
        runTest {
            now += 5.minutes

            val order = assertIs<SellEstimate.Priced>(repository.estimateSell(nvda, SellAmount.Units(BigDecimal("0.1"))))

            coVerify { investRepository.refreshMarket() }
            assertEquals(BigDecimal("0.100000000000000000"), order.unitsIn)
            assertFailsWith<IllegalArgumentException> { repository.prepareSell(nvda, SellAmount.Units(BigDecimal("0.1"))) }
            assertTrue(api.generateRequests.isEmpty())
            assertEquals(SellEstimate.NoPrice, repository.estimateSell(nvda, SellAmount.Usd(BigDecimal(50))))
        }

    @Test
    fun `selling exactly the minimum in dollars goes through`() =
        runTest {
            val prepared = repository.prepareSell(nvda, SellAmount.Usd(BigDecimal(40)))

            assertTrue(prepared.usdIn < BigDecimal(40))
            assertEquals(1, api.generateRequests.size)
        }

    @Test
    fun `one intent is never submitted twice`() =
        runTest {
            val prepared = repository.prepareSell(nvda, SellAmount.All)
            repository.executeSell(prepared)

            assertFailsWith<IllegalStateException> { repository.executeSell(prepared) }
            assertEquals(1, api.submitted.size)
        }
}
