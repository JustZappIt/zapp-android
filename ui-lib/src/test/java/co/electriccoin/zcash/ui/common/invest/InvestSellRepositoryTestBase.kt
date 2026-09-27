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

/** The fakes behind the sell repository's tests: a holding of 0.440974 NVDAon (18 decimals) at $224.46, which a sale quotes at 0.0634 ZEC. */
@Suppress("MaxLineLength")
internal abstract class InvestSellRepositoryTestBase {
    protected val nvda = InvestAssets.curated.first { it.ticker == "NVDA" }
    protected var now = Instant.parse("2026-09-28T14:00:00Z")
    protected val api = FakeApi()
    protected val checkpoints = FakeCheckpoints()
    protected val session = mockk<PrivateAccountSession>()
    protected val biometrics = mockk<BiometricRepository>(relaxed = true)
    protected val holdings = MutableStateFlow("440974000000000000")
    protected var addressesHandedOut = 0

    protected val investRepository =
        mockk<InvestRepository>(relaxed = true) {
            coEvery { isAccountSupported() } returns true
            every { market } returns
                MutableStateFlow(InvestMarket(listOf(MarketAsset(nvda, BigDecimal("224.46"))), Instant.parse("2026-09-28T14:00:00Z")))
        }

    protected val repository =
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
            BalancesResponse(
                listOf(
                    AccountBalance(tokenId = nvda.assetId, available = holdings.value, source = "private"),
                    AccountBalance(tokenId = nvda.assetId, available = "1000000000000000000", source = "public"),
                ),
            )
        }
    }

    protected fun pastDeadline(start: Instant = now) =
        InvestBuyCheckpoint(
            depositAddress = DEPOSIT,
            assetId = nvda.assetId,
            createdAtMillis = 0,
            intentDeadlineMillis = (start + 8.minutes).toEpochMilliseconds(),
            baseUnits = "440974000000000000",
            heldBeforeBaseUnits = "440974000000000000",
        )

    protected fun notFound() = InvestApiException.Api(404, "Deposit address not found", "c")

    protected fun status(status: SwapStatus) =
        SwapStatusResponseDto(
            quoteResponse = api.response(api.lastRequest ?: api.requestFor()),
            status = status,
            updatedAt = "2026-09-28T14:05:00Z",
            swapDetails = SwapDetails(amountOutFormatted = BigDecimal("0.0634")),
        )

    protected inner class FakeApi : InvestApiProvider {
        val quotes = mutableListOf<QuoteRequest>()
        val generateRequests = mutableListOf<GenerateIntentRequest>()
        val submitted = mutableListOf<SubmitIntentRequest>()
        val statuses = mutableListOf<Any>()
        var lastRequest: QuoteRequest? = null
        var quoteFailure: InvestApiException? = null
        var submitFailure: InvestApiException? = null
        var echoTamper: (QuoteRequest) -> QuoteRequest = { it }
        var payloadAmount: String? = null
        var extraTransferField = ""
        var checkpointsAtSubmit: List<String>? = null

        fun payload(): String =
            "{\"signer_id\":\"$TEST_ACCOUNT_ID\",\"verifying_contract\":\"intents.near\",\"deadline\":\"2026-09-28T14:08:00.000Z\"," +
                "\"nonce\":\"Vij2xgAlKBKzAMg6wgOB2RgAwGTZ2YDZGAECAwQFBgc=\",\"intents\":[{\"intent\":\"transfer\"," +
                "\"receiver_id\":\"$DEPOSIT\",$extraTransferField\"tokens\":{\"${nvda.assetId}\":\"${payloadAmount ?: lastRequest?.amount?.toPlainString()}\"}}]}"

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

        override suspend fun checkStatus(depositAddress: String): SwapStatusResponseDto =
            when (val next = statuses.removeAt(0)) {
                is Throwable -> throw next
                else -> next as SwapStatusResponseDto
            }

        override suspend fun getOndoTokens(): List<NearTokenDto> = error("unused")

        override suspend fun submitDeposit(request: SubmitDepositTransactionRequest) = error("unused")

        override suspend fun authenticate(request: AuthenticateRequest): AuthenticateResponse = error("unused")

        override suspend fun getBalances(accessToken: String): BalancesResponse = error("unused")
    }

    protected class FakeCheckpoints : InvestSellCheckpointStorageProvider {
        val items = MutableStateFlow<List<InvestBuyCheckpoint>>(emptyList())

        override fun observe(): Flow<List<InvestBuyCheckpoint>> = items

        override suspend fun add(checkpoint: InvestBuyCheckpoint) {
            // Like the real store: one checkpoint per deposit address, replaced when added again.
            items.value = items.value.filterNot { it.depositAddress == checkpoint.depositAddress } + checkpoint
        }

        override suspend fun remove(depositAddress: String) {
            items.value = items.value.filterNot { it.depositAddress == depositAddress }
        }
    }

    companion object {
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
