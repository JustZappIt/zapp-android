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

// A holding of 0.440974 NVDAon (18 decimals) at $224.46; a sale quotes 0.0634 ZEC.
@Suppress("MaxLineLength", "LargeClass")
class InvestSellRepositoryImplTest {
    private val nvda = InvestAssets.curated.first { it.ticker == "NVDA" }
    private var now = Instant.parse("2026-09-28T14:00:00Z")
    private val api = FakeApi()
    private val checkpoints = FakeCheckpoints()
    private val session = mockk<PrivateAccountSession>()
    private val biometrics = mockk<BiometricRepository>(relaxed = true)
    private val holdings = MutableStateFlow("440974000000000000")
    private var addressesHandedOut = 0

    private val investRepository =
        mockk<InvestRepository>(relaxed = true) {
            coEvery { isAccountSupported() } returns true
            every { market } returns
                MutableStateFlow(InvestMarket(listOf(MarketAsset(nvda, BigDecimal("224.46"))), Instant.parse("2026-09-28T14:00:00Z")))
        }

    private val repository =
        InvestSellRepositoryImpl(
            api = api,
            session = session,
            keys = PrivateAccountKeyProvider(SeedPhraseSource { TEST_MNEMONIC.toCharArray() }),
            wallet =
                object : OfframpBridgeWallet {
                    override suspend fun zcashAddress(): String = PAYOUT.also { addressesHandedOut++ }

                    override suspend fun sendZecDeposit(quote: SwapQuote): String = error("a sale sends no ZEC")

                    override suspend fun spendableZec(): Zatoshi = Zatoshi(0)
                },
            investRepository = investRepository,
            swapAssets =
                object : InvestSwapAssetSource {
                    override suspend fun investSwapAssets(): List<SwapAsset> = listOf(NVDA_SWAP_ASSET)
                },
            biometricRepository = biometrics,
            checkpoints = checkpoints,
            now = { now },
            pollIntervalMillis = 1,
        )

    init {
        coEvery { session.balances() } answers {
            BalancesResponse(listOf(AccountBalance(tokenId = nvda.assetId, available = holdings.value, source = "private")))
        }
    }

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
            api.submitFailure = InvestApiException.Api(400, "Invalid signed data", "c")
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
    fun `one intent is never submitted twice`() =
        runTest {
            val prepared = repository.prepareSell(nvda, SellAmount.All)
            repository.executeSell(prepared)

            assertFailsWith<IllegalStateException> { repository.executeSell(prepared) }
            assertEquals(1, api.submitted.size)
        }

    @Test
    fun `progress runs to sent and refreshes holdings`() =
        runTest {
            api.statuses += listOf(status(SwapStatus.PENDING_DEPOSIT), status(SwapStatus.PROCESSING), status(SwapStatus.SUCCESS))

            val progress = repository.observeSell(DEPOSIT).toList()

            assertEquals(
                listOf(SellProgress.Authorised(DEPOSIT), SellProgress.Selling(DEPOSIT), SellProgress.Sent(DEPOSIT, BigDecimal("0.0634"))),
                progress,
            )
            coVerify { investRepository.refreshHoldings() }
        }

    @Test
    fun `an intent past its deadline is reported unsold and cleared`() =
        runTest {
            checkpoints.add(InvestBuyCheckpoint(DEPOSIT, nvda.assetId, 0, intentDeadlineMillis = (now + 8.minutes).toEpochMilliseconds()))
            api.statuses += status(SwapStatus.PENDING_DEPOSIT)
            now += 14.minutes

            assertEquals(listOf(SellProgress.NotSold(DEPOSIT)), repository.observeSell(DEPOSIT).toList())
            assertTrue(checkpoints.items.value.isEmpty())
        }

    @Test
    fun `a refund returns the stock and a failure stays listed`() =
        runTest {
            api.statuses += status(SwapStatus.REFUNDED)
            assertEquals(listOf(SellProgress.ReturnedToAccount(DEPOSIT)), repository.observeSell(DEPOSIT).toList())

            checkpoints.add(InvestBuyCheckpoint(DEPOSIT, nvda.assetId, 0))
            api.statuses += status(SwapStatus.FAILED)
            assertEquals(listOf(SellProgress.NeedsAttention(DEPOSIT, DEPOSIT)), repository.observeSell(DEPOSIT).toList())
            assertEquals(1, checkpoints.items.value.size)
            repository.dismissSell(DEPOSIT)
            assertNull(checkpoints.items.value.firstOrNull())
        }

    private fun status(status: SwapStatus) =
        SwapStatusResponseDto(
            quoteResponse = api.response(api.lastRequest ?: api.requestFor()),
            status = status,
            updatedAt = "2026-09-28T14:05:00Z",
            swapDetails = SwapDetails(amountOutFormatted = BigDecimal("0.0634")),
        )

    private inner class FakeApi : InvestApiProvider {
        val quotes = mutableListOf<QuoteRequest>()
        val generateRequests = mutableListOf<GenerateIntentRequest>()
        val submitted = mutableListOf<SubmitIntentRequest>()
        val statuses = ArrayDeque<SwapStatusResponseDto>()
        var lastRequest: QuoteRequest? = null
        var quoteFailure: InvestApiException? = null
        var submitFailure: InvestApiException? = null
        var echoTamper: (QuoteRequest) -> QuoteRequest = { it }
        var payloadAmount: String? = null
        var checkpointsAtSubmit: List<String>? = null

        fun payload(): String =
            "{\"signer_id\":\"$TEST_ACCOUNT_ID\",\"verifying_contract\":\"intents.near\",\"deadline\":\"2026-09-28T14:08:00.000Z\"," +
                "\"nonce\":\"Vij2xgAlKBKzAMg6wgOB2RgAwGTZ2YDZGAECAwQFBgc=\",\"intents\":[{\"intent\":\"transfer\"," +
                "\"receiver_id\":\"$DEPOSIT\",\"tokens\":{\"${nvda.assetId}\":\"${payloadAmount ?: lastRequest?.amount?.toPlainString()}\"}}]}"

        fun requestFor() =
            QuoteRequest(
                dry = false,
                slippageTolerance = 100,
                originAsset = nvda.assetId,
                destinationAsset = "nep141:zec.omft.near",
                amount = BigDecimal("440974000000000000"),
                refundTo = TEST_ACCOUNT_ID,
                recipient = PAYOUT,
                deadline = now,
                appFees = emptyList(),
            )

        override suspend fun requestQuote(request: QuoteRequest): QuoteResponseDto {
            quoteFailure?.let { throw it }
            quotes += request
            lastRequest = request
            return response(request)
        }

        fun response(request: QuoteRequest): QuoteResponseDto {
            val units = request.amount.movePointLeft(18)
            return QuoteResponseDto(
                timestamp = now,
                quoteRequest = echoTamper(request),
                quote =
                    QuoteDetails(
                        depositAddress = if (request.dry) null else DEPOSIT,
                        amountIn = request.amount,
                        amountInFormatted = units,
                        amountInUsd = units.multiply(BigDecimal("224.46")),
                        minAmountIn = request.amount,
                        amountOut = BigDecimal(6_340_000),
                        amountOutFormatted = BigDecimal("0.0634"),
                        amountOutUsd = BigDecimal("97.87"),
                        minAmountOut = BigDecimal(6_280_000),
                        deadline = if (request.dry) null else now + 30.minutes,
                        timeEstimate = 140,
                        refundFee = BigDecimal.ZERO,
                        withdrawFee = BigDecimal(64_000),
                    ),
            )
        }

        override suspend fun generateIntent(request: GenerateIntentRequest): GenerateIntentResponse {
            generateRequests += request
            return GenerateIntentResponse(GeneratedIntent(standard = "erc191", payload = payload()), correlationId = "gen-cid")
        }

        override suspend fun submitIntent(request: SubmitIntentRequest): SubmitIntentResponse {
            checkpointsAtSubmit = checkpoints.items.value.map { it.depositAddress }
            submitted += request
            submitFailure?.let { throw it }
            return SubmitIntentResponse(intentHash = "hash", correlationId = "sub-cid")
        }

        override suspend fun checkStatus(depositAddress: String): SwapStatusResponseDto = statuses.removeFirst()

        override suspend fun getOndoTokens(): List<NearTokenDto> = error("unused")

        override suspend fun submitDeposit(request: SubmitDepositTransactionRequest) = error("unused")

        override suspend fun authenticate(request: AuthenticateRequest): AuthenticateResponse = error("unused")

        override suspend fun getBalances(accessToken: String): BalancesResponse = error("unused")
    }

    private class FakeCheckpoints : InvestSellCheckpointStorageProvider {
        val items = MutableStateFlow<List<InvestBuyCheckpoint>>(emptyList())

        override fun observe(): Flow<List<InvestBuyCheckpoint>> = items

        override suspend fun add(checkpoint: InvestBuyCheckpoint) {
            items.value = items.value + checkpoint
        }

        override suspend fun remove(depositAddress: String) {
            items.value = items.value.filterNot { it.depositAddress == depositAddress }
        }
    }

    private companion object {
        const val PAYOUT = "u1freshpayoutaddress"
        const val DEPOSIT = "0000000000000000000000000000000000000000000000000000000000000001"

        val NVDA_SWAP_ASSET =
            DynamicSwapAsset(
                tokenTicker = "NVDAon",
                tokenName = StringResource.ByString("NVDAon"),
                tokenIcon = imageRes("NVDAon"),
                usdPrice = BigDecimal("224.46"),
                assetId = "nep141:bnb-0xa9ee28c80f960b889dfbd1902055218cba016f75.omdep.near",
                decimals = 18,
                blockchain = SwapBlockchain("bsc", StringResource.ByString("bsc"), imageRes("bsc")),
            )
    }
}
