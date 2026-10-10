package co.electriccoin.zcash.ui.common.invest

import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.model.AccountBalance
import co.electriccoin.zcash.ui.common.invest.model.AuthenticateRequest
import co.electriccoin.zcash.ui.common.invest.model.AuthenticateResponse
import co.electriccoin.zcash.ui.common.invest.model.BalancesResponse
import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.GenerateIntentRequest
import co.electriccoin.zcash.ui.common.invest.model.GenerateIntentResponse
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.model.SubmitIntentRequest
import co.electriccoin.zcash.ui.common.invest.model.SubmitIntentResponse
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpoint
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepositoryImpl
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeGuard
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.model.near.Confidentiality
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
import co.electriccoin.zcash.ui.common.model.near.QuoteDetails
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.RecipientType
import co.electriccoin.zcash.ui.common.model.near.SubmitDepositTransactionRequest
import co.electriccoin.zcash.ui.common.model.near.SwapDetails
import co.electriccoin.zcash.ui.common.model.near.SwapStatus
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.provider.BridgeAuthorizationCancelledException
import co.electriccoin.zcash.ui.common.provider.OfframpBridgeWallet
import co.electriccoin.zcash.ui.common.provider.SwapAssetProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.account.SeedPhraseSource
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Fixtures follow a real $100 NVIDIA dry quote from 2026-09-26 (correlationId c8f4806d-…).
@Suppress("MaxLineLength", "LargeClass")
internal abstract class InvestRepositoryImplTestBase {
    protected val nvda = InvestAssets.curated.first { it.ticker == "NVDA" }
    protected var now = Instant.parse("2026-09-28T14:00:00Z")
    protected val api = FakeApi()
    protected val wallet = FakeWallet()
    protected val checkpoints = FakeCheckpoints()
    protected var residence = InvestSettings(countryCode = "AE", setupComplete = true)
    protected val sellCheckpoints = MutableStateFlow<List<InvestBuyCheckpoint>>(emptyList())
    protected val session = mockk<PrivateAccountSession>()
    protected val keys = PrivateAccountKeyProvider(SeedPhraseSource { TEST_MNEMONIC.toCharArray() })
    protected var currentAccount: WalletAccount = mockk<ZashiAccount>()

    protected val repository =
        InvestRepositoryImpl(
            api = api,
            session = session,
            keys = keys,
            wallet = wallet,
            accountDataSource = mockk<AccountDataSource> { coEvery { getSelectedAccount() } answers { currentAccount } },
            swapAssetProvider = FakeSwapAssetProvider,
            synchronizerProvider =
                mockk<SynchronizerProvider> {
                    coEvery { getSynchronizer() } returns mockk { coEvery { validateAddress(any()) } returns AddressType.Transparent }
                },
            checkpoints = checkpoints,
            settings = mockk<InvestSettingsRepository> { coEvery { get() } answers { residence } },
            trades =
                InvestTradeGuard(
                    buys = checkpoints,
                    sells =
                        mockk<InvestSellCheckpointStorageProvider> {
                            every { observe() } returns
                                sellCheckpoints
                        }
                ),
            now = { now },
            pollIntervalMillis = 1,
        )

    protected fun status(
        status: SwapStatus,
        amountOut: BigDecimal? = null,
        refunded: BigDecimal? = null,
    ) = SwapStatusResponseDto(
        quoteResponse = api.response(api.lastRequest ?: executableRequest()),
        status = status,
        updatedAt = "2026-09-28T14:05:00Z",
        swapDetails = SwapDetails(amountOutFormatted = amountOut, refundedAmountFormatted = refunded),
    )

    protected fun executableRequest() =
        QuoteRequest(
            dry = false,
            slippageTolerance = 100,
            originAsset = "nep141:zec.omft.near",
            destinationAsset = nvda.assetId,
            amount = BigDecimal(6_478_278),
            refundTo = FRESH_UA,
            recipient = TEST_ACCOUNT_ID,
            deadline = now,
            appFees = emptyList(),
        )

    protected inner class FakeApi : InvestApiProvider {
        val quotes = mutableListOf<QuoteRequest>()
        var lastRequest: QuoteRequest? = null
        var quoteFailure: InvestApiException? = null
        var echoTamper: (QuoteRequest) -> QuoteRequest = { it }
        var amountInOverride: BigDecimal? = null
        var usdIn: BigDecimal? = null
        var deadline: Instant? = null
        var depositAddress = DEPOSIT
        val statuses = ArrayDeque<SwapStatusResponseDto?>()

        var tokenLoads = 0
        val statusFailures = ArrayDeque<InvestApiException>()

        override suspend fun getOndoTokens(): List<NearTokenDto> =
            listOf(
                NearTokenDto("nep141:zec.omft.near", 8, "zec", "ZEC", BigDecimal("1543.62")),
                NearTokenDto(nvda.assetId, 18, "bsc", "NVDAon", BigDecimal("224.46")),
                NearTokenDto(InvestAssets.curated[1].assetId, 18, "bsc", "TSLAon", null),
                NearTokenDto("nep141:wrap.near", 24, "near", "wNEAR", BigDecimal("3")),
            ).also { tokenLoads++ }

        override suspend fun requestQuote(request: QuoteRequest): QuoteResponseDto {
            quoteFailure?.let { throw it }
            quotes += request
            lastRequest = request
            return response(request)
        }

        fun response(request: QuoteRequest): QuoteResponseDto =
            QuoteResponseDto(
                timestamp = now,
                quoteRequest = echoTamper(request),
                quote =
                    QuoteDetails(
                        depositAddress = if (request.dry) null else depositAddress,
                        amountIn = amountInOverride ?: request.amount,
                        amountInFormatted = request.amount.movePointLeft(8),
                        amountInUsd = usdIn ?: request.amount.movePointLeft(8).multiply(BigDecimal("1543.62")),
                        minAmountIn = request.amount,
                        amountOut = BigDecimal("440974000000000000"),
                        amountOutFormatted = BigDecimal("0.440974"),
                        amountOutUsd = BigDecimal("99.01"),
                        minAmountOut = BigDecimal("436564260000000000"),
                        deadline = if (request.dry) null else deadline ?: (now + 120.minutes),
                        timeEstimate = 470,
                        refundFee = BigDecimal(32_000),
                        withdrawFee = BigDecimal.ZERO,
                    ),
            )

        override suspend fun checkStatus(depositAddress: String): SwapStatusResponseDto {
            statusFailures.removeFirstOrNull()?.let { throw it }
            return statuses.removeFirst() ?: throw InvestApiException.Unreachable(IllegalStateException("poll"))
        }

        override suspend fun submitDeposit(request: SubmitDepositTransactionRequest) = error("unused")

        override suspend fun generateIntent(request: GenerateIntentRequest): GenerateIntentResponse = error("unused")

        override suspend fun submitIntent(request: SubmitIntentRequest): SubmitIntentResponse = error("unused")

        override suspend fun authenticate(request: AuthenticateRequest): AuthenticateResponse = error("unused")

        override suspend fun getBalances(accessToken: String): BalancesResponse = error("unused")
    }

    protected inner class FakeWallet : OfframpBridgeWallet {
        var spendable = Zatoshi(100_000_000)
        var sendFailure: Exception? = null
        var checkpointsAtSend: List<String>? = null

        var addressesHandedOut = 0

        override suspend fun zcashAddress(): String = FRESH_UA.also { addressesHandedOut++ }

        override suspend fun sendZecDeposit(quote: SwapQuote): String {
            checkpointsAtSend = checkpoints.items.value.map { it.depositAddress }
            sendFailure?.let { throw it }
            return "txid"
        }

        override suspend fun spendableZec(): Zatoshi = spendable
    }

    protected class FakeCheckpoints : InvestBuyCheckpointStorageProvider {
        val items = MutableStateFlow<List<InvestBuyCheckpoint>>(emptyList())

        override fun observe(): Flow<List<InvestBuyCheckpoint>> = items

        override suspend fun add(checkpoint: InvestBuyCheckpoint) {
            items.value = items.value + checkpoint
        }

        override suspend fun remove(depositAddress: String) {
            items.value = items.value.filterNot { it.depositAddress == depositAddress }
        }
    }

    protected object FakeSwapAssetProvider : SwapAssetProvider {
        override fun get(
            tokenTicker: String,
            chainTicker: String,
            usdPrice: BigDecimal?,
            assetId: String,
            decimals: Int,
        ): SwapAsset {
            val chain = SwapBlockchain(chainTicker, StringResource.ByString(chainTicker), imageRes(chainTicker))
            return if (tokenTicker.equals("zec", ignoreCase = true)) {
                ZecSwapAsset(
                    tokenTicker = tokenTicker,
                    tokenName = StringResource.ByString("Zcash"),
                    tokenIcon = imageRes("zec"),
                    blockchain = chain,
                    usdPrice = usdPrice,
                    assetId = assetId,
                    decimals = decimals,
                )
            } else {
                DynamicSwapAsset(
                    tokenTicker = tokenTicker,
                    tokenName = StringResource.ByString(tokenTicker),
                    tokenIcon = imageRes(tokenTicker),
                    usdPrice = usdPrice,
                    assetId = assetId,
                    decimals = decimals,
                    blockchain = chain,
                )
            }
        }
    }

    protected companion object {
        const val FRESH_UA = "u1freshaddress"
        const val DEPOSIT = "t1depositaddress"
    }
}
