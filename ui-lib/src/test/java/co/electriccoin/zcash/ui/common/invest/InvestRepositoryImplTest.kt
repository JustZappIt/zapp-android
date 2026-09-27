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
class InvestRepositoryImplTest {
    private val nvda = InvestAssets.curated.first { it.ticker == "NVDA" }
    private var now = Instant.parse("2026-09-28T14:00:00Z")
    private val api = FakeApi()
    private val wallet = FakeWallet()
    private val checkpoints = FakeCheckpoints()
    private val sellCheckpoints = MutableStateFlow<List<InvestBuyCheckpoint>>(emptyList())
    private val session = mockk<PrivateAccountSession>()
    private val keys = PrivateAccountKeyProvider(SeedPhraseSource { TEST_MNEMONIC.toCharArray() })
    private var currentAccount: WalletAccount = mockk<ZashiAccount>()

    private val repository =
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

    @Test
    fun `below the minimum needs no quote`() =
        runTest {
            assertEquals(BuyEstimate.BelowMinimum(BigDecimal(40)), repository.estimateBuy(nvda, BigDecimal("39.99")))
            assertTrue(api.quotes.isEmpty())
        }

    @Test
    fun `more than the spendable shielded ZEC is refused before quoting`() =
        runTest {
            wallet.spendable = Zatoshi(6_478_278) // exactly the $100, with nothing left for the network fee

            val estimate = repository.estimateBuy(nvda, BigDecimal(100))

            assertEquals(BuyEstimate.InsufficientZec(BigDecimal("0.06478278")), estimate)
            assertTrue(api.quotes.isEmpty())
        }

    @Test
    fun `an estimate is a dry quote into the private account`() =
        runTest {
            val estimate = assertIs<BuyEstimate.Priced>(repository.estimateBuy(nvda, BigDecimal(100)))

            val request = api.quotes.single()
            assertTrue(request.dry)
            assertEquals(BigDecimal(6_478_278), request.amount) // $100 at $1,543.62, rounded down to the zat
            assertEquals(TEST_ACCOUNT_ID, request.recipient)
            assertEquals(RecipientType.CONFIDENTIAL_INTENTS, request.recipientType)
            assertEquals(Confidentiality.BASIC, request.confidentiality)
            // Not the Receive address: one fresh address, reused for every estimate this session.
            assertEquals(FRESH_UA, request.refundTo)
            repository.estimateBuy(nvda, BigDecimal(200))
            assertEquals(1, wallet.addressesHandedOut)
            assertEquals(nvda.assetId, request.destinationAsset)
            assertEquals(BigDecimal("0.440974"), estimate.unitsOut)
            assertEquals(BigDecimal("0.99"), estimate.feesUsd.setScale(2, java.math.RoundingMode.HALF_UP))
            assertEquals(BigDecimal("0.00032000"), estimate.refundFeeZec)
        }

    @Test
    fun `no liquidity is a normal estimate, not an error`() =
        runTest {
            api.quoteFailure = InvestApiException.NoPrice("cid")

            assertEquals(BuyEstimate.NoPrice, repository.estimateBuy(nvda, BigDecimal(100)))
        }

    @Test
    fun `a prepared buy uses a fresh refund address and holds the price ten minutes`() =
        runTest {
            val prepared = repository.prepareBuy(nvda, BigDecimal(100))

            val request = api.quotes.single()
            assertEquals(false, request.dry)
            assertEquals(FRESH_UA, request.refundTo)
            assertEquals(1, wallet.addressesHandedOut)
            assertEquals(DEPOSIT, prepared.quote.depositAddress.address)
            assertEquals(BigDecimal("0.440974"), prepared.unitsOutExpected)
            assertEquals(BigDecimal("0.436564260000000000"), prepared.unitsOutMin)
            assertEquals(now + 10.minutes, prepared.expiresAt)
        }

    @Test
    fun `a live quote that differs from the request is never shown`() =
        runTest {
            listOf<(QuoteRequest) -> QuoteRequest>(
                { it.copy(recipient = "0x9858effd232b4033e47d90003d41ec34ecaeda94") },
                { it.copy(recipientType = RecipientType.INTENTS) },
                { it.copy(refundTo = "u1someoneelse") },
                { it.copy(swapType = co.electriccoin.zcash.ui.common.model.near.SwapType.EXACT_OUTPUT) },
                { it.copy(destinationAsset = InvestAssets.curated[1].assetId) },
                { it.copy(originAsset = "nep141:wrap.near") },
                { it.copy(dry = true) },
            ).forEach { tamper ->
                api.echoTamper = tamper
                assertFailsWith<IllegalArgumentException> { repository.prepareBuy(nvda, BigDecimal(100)) }
            }
            api.echoTamper = { it }
            api.amountInOverride = BigDecimal(6_478_279)
            assertFailsWith<IllegalArgumentException> { repository.prepareBuy(nvda, BigDecimal(100)) }
        }

    @Test
    fun `the checkpoint is written before any ZEC moves`() =
        runTest {
            val prepared = repository.prepareBuy(nvda, BigDecimal(100))

            val deposit = repository.executeBuy(prepared)

            assertEquals(DEPOSIT, deposit)
            assertEquals(listOf(DEPOSIT), wallet.checkpointsAtSend)
            assertEquals(listOf(DEPOSIT), checkpoints.items.value.map { it.depositAddress })
        }

    @Test
    fun `a cancelled send leaves nothing to resume`() =
        runTest {
            val prepared = repository.prepareBuy(nvda, BigDecimal(100))
            wallet.sendFailure = BridgeAuthorizationCancelledException()

            assertFailsWith<BridgeAuthorizationCancelledException> { repository.executeBuy(prepared) }

            assertTrue(checkpoints.items.value.isEmpty())
        }

    @Test
    fun `a failure that may follow a broadcast keeps the buy to resume`() =
        runTest {
            val prepared = repository.prepareBuy(nvda, BigDecimal(100))
            wallet.sendFailure = IllegalStateException("ZEC bridge deposit did not succeed: Partial")

            assertFailsWith<IllegalStateException> { repository.executeBuy(prepared) }

            assertEquals(listOf(DEPOSIT), checkpoints.items.value.map { it.depositAddress })
        }

    @Test
    fun `one quote is never paid twice`() =
        runTest {
            val prepared = repository.prepareBuy(nvda, BigDecimal(100))
            repository.executeBuy(prepared)
            wallet.checkpointsAtSend = null

            assertFailsWith<IllegalStateException> { repository.executeBuy(prepared) }
            assertNull(wallet.checkpointsAtSend)
        }

    @Test
    fun `a stock being sold can't be bought until the sale is final`() =
        runTest {
            val prepared = repository.prepareBuy(nvda, BigDecimal(100))
            sellCheckpoints.value = listOf(InvestBuyCheckpoint("sell-deposit", nvda.assetId, 0))

            assertFailsWith<IllegalStateException> { repository.prepareBuy(nvda, BigDecimal(100)) }
            assertFailsWith<IllegalStateException> { repository.executeBuy(prepared) }
            assertNull(wallet.checkpointsAtSend)
            assertTrue(checkpoints.items.value.isEmpty())
        }

    @Test
    fun `a round ZEC amount is not mistaken for a mismatch`() =
        runTest {
            // $100.026576 at $1,543.62 is exactly 0.0648 ZEC = 6,480,000 zats, a value ending in zeros.
            val prepared = repository.prepareBuy(nvda, BigDecimal("100.026576"))

            assertEquals(0, BigDecimal("0.0648").compareTo(prepared.zecIn))
        }

    @Test
    fun `a quote priced far from the amount entered is refused`() =
        runTest {
            api.usdIn = BigDecimal("94.00")

            assertFailsWith<IllegalArgumentException> { repository.prepareBuy(nvda, BigDecimal(100)) }
        }

    @Test
    fun `prices older than a minute are fetched again before a buy`() =
        runTest {
            repository.refreshMarket()
            now += 59.seconds
            repository.prepareBuy(nvda, BigDecimal(100))
            assertEquals(1, api.tokenLoads)

            now += 1.seconds
            repository.prepareBuy(nvda, BigDecimal(100))
            assertEquals(2, api.tokenLoads)
        }

    @Test
    fun `a buy never funded by its deadline expires and stops polling`() =
        runTest {
            checkpoints.add(InvestBuyCheckpoint(DEPOSIT, nvda.assetId, 0))
            api.deadline = now + 30.minutes
            api.statuses += status(SwapStatus.PENDING_DEPOSIT)
            api.statuses += status(SwapStatus.PENDING_DEPOSIT)
            now += 31.minutes // past the deadline, within the grace period: still waiting
            val waiting = repository.observeBuy(DEPOSIT).first()
            assertEquals(BuyProgress.SendingZec(DEPOSIT), waiting)
            now += 30.minutes

            assertEquals(listOf(BuyProgress.Expired(DEPOSIT)), repository.observeBuy(DEPOSIT).toList())
            assertTrue(checkpoints.items.value.isEmpty())
        }

    @Test
    fun `the checkpoint is cleared even if the collector stops at the final state`() =
        runTest {
            checkpoints.add(InvestBuyCheckpoint(DEPOSIT, nvda.assetId, 0))
            coEvery { session.balances() } returns BalancesResponse(emptyList())
            api.statuses += status(SwapStatus.SUCCESS, amountOut = BigDecimal("0.440974"))

            repository.observeBuy(DEPOSIT).first { it.isFinal }

            assertTrue(checkpoints.items.value.isEmpty())
        }

    @Test
    fun `rate limiting is not mistaken for a permanent refusal`() =
        runTest {
            repeat(5) { api.statusFailures += InvestApiException.Api(429, "Too many requests", null) }
            api.statuses += status(SwapStatus.SUCCESS, amountOut = BigDecimal("0.440974"))
            coEvery { session.balances() } returns BalancesResponse(emptyList())

            assertEquals(
                listOf(BuyProgress.Held(DEPOSIT, BigDecimal("0.440974"))),
                repository.observeBuy(DEPOSIT).toList(),
            )
        }

    @Test
    fun `a buy that needs attention can be dismissed`() =
        runTest {
            checkpoints.add(InvestBuyCheckpoint(DEPOSIT, nvda.assetId, 0))

            repository.dismissBuy(DEPOSIT)

            assertTrue(checkpoints.items.value.isEmpty())
        }

    @Test
    fun `a Keystone account can't buy`() =
        runTest {
            val prepared = repository.prepareBuy(nvda, BigDecimal(100))
            currentAccount = mockk<KeystoneAccount>()

            assertEquals(false, repository.isAccountSupported())
            assertFailsWith<IllegalStateException> { repository.prepareBuy(nvda, BigDecimal(100)) }
            assertFailsWith<IllegalStateException> { repository.executeBuy(prepared) }
            assertNull(wallet.checkpointsAtSend)
        }

    @Test
    fun `a status 1Click keeps refusing ends as needs attention and stays listed`() =
        runTest {
            checkpoints.add(InvestBuyCheckpoint(DEPOSIT, nvda.assetId, 0))
            repeat(3) { api.statusFailures += InvestApiException.Api(404, "Not found", null) }

            assertEquals(listOf(BuyProgress.NeedsAttention(DEPOSIT, DEPOSIT)), repository.observeBuy(DEPOSIT).toList())
            assertEquals(listOf(DEPOSIT), checkpoints.items.value.map { it.depositAddress })
        }

    @Test
    fun `a price no longer held is never paid`() =
        runTest {
            val prepared = repository.prepareBuy(nvda, BigDecimal(100))
            now += 10.minutes

            assertFailsWith<IllegalStateException> { repository.executeBuy(prepared) }
            assertTrue(wallet.checkpointsAtSend == null)
        }

    @Test
    fun `progress follows 1Click through a failed poll to held, then clears the checkpoint`() =
        runTest {
            checkpoints.add(InvestBuyCheckpoint(DEPOSIT, nvda.assetId, 0))
            coEvery { session.balances() } returns BalancesResponse(emptyList())
            api.statuses +=
                listOf(
                    status(SwapStatus.PENDING_DEPOSIT),
                    status(SwapStatus.INCOMPLETE_DEPOSIT),
                    null, // an unreachable poll
                    status(SwapStatus.PROCESSING),
                    status(SwapStatus.SUCCESS, amountOut = BigDecimal("0.440974")),
                )

            val progress = repository.observeBuy(DEPOSIT).toList()

            assertEquals(
                listOf(
                    BuyProgress.SendingZec(DEPOSIT),
                    BuyProgress.PaymentReceived(DEPOSIT, incomplete = true),
                    BuyProgress.Buying(DEPOSIT),
                    BuyProgress.Held(DEPOSIT, BigDecimal("0.440974")),
                ),
                progress,
            )
            assertTrue(checkpoints.items.value.isEmpty())
        }

    @Test
    fun `refunds and failures are final`() =
        runTest {
            api.statuses += status(SwapStatus.REFUNDED, refunded = BigDecimal("0.06446278"))
            assertEquals(
                listOf(BuyProgress.Refunded(DEPOSIT, BigDecimal("0.06446278"))),
                repository.observeBuy(DEPOSIT).toList(),
            )
            api.statuses += status(SwapStatus.FAILED)
            assertEquals(listOf(BuyProgress.NeedsAttention(DEPOSIT, DEPOSIT)), repository.observeBuy(DEPOSIT).toList())
        }

    @Test
    fun `holdings are the curated positions, valued at the latest price`() =
        runTest {
            coEvery { session.balances() } returns
                BalancesResponse(
                    listOf(
                        AccountBalance(tokenId = nvda.assetId, available = "440974000000000000", source = "private"),
                        AccountBalance(tokenId = "nep141:wrap.near", available = "5", source = "private"),
                        AccountBalance(tokenId = InvestAssets.curated[1].assetId, available = "0", source = "private"),
                    ),
                )

            repository.refreshHoldings()

            val holdings = requireNotNull(repository.holdings.value)
            val holding = holdings.items.single()
            assertEquals(nvda, holding.asset)
            assertEquals(BigDecimal("0.440974"), holding.units)
            assertEquals(0, BigDecimal("98.98102404").compareTo(holding.usdValue))
            assertEquals(holding.usdValue, holdings.totalUsd)
            assertEquals(false, holdings.isStale)
        }

    @Test
    fun `a failed refresh keeps the last holdings and marks them stale`() =
        runTest {
            coEvery { session.balances() } returns BalancesResponse(emptyList())
            repository.refreshHoldings()
            coEvery { session.balances() } throws InvestApiException.Unreachable(IllegalStateException())

            assertFailsWith<InvestApiException.Unreachable> { repository.refreshHoldings() }

            assertEquals(true, repository.holdings.value?.isStale)
            assertNull(repository.holdings.value?.totalUsd)
        }

    @Test
    fun `the market lists all ten curated stocks with 1Click prices`() =
        runTest {
            repository.refreshMarket()

            val market = requireNotNull(repository.market.value)
            assertEquals(InvestAssets.curated, market.assets.map { it.asset })
            assertEquals(BigDecimal("224.46"), market.assets.first { it.asset == nvda }.usdPrice)
            assertEquals(2, repository.investSwapAssets().size)
        }

    private fun status(
        status: SwapStatus,
        amountOut: BigDecimal? = null,
        refunded: BigDecimal? = null,
    ) = SwapStatusResponseDto(
        quoteResponse = api.response(api.lastRequest ?: executableRequest()),
        status = status,
        updatedAt = "2026-09-28T14:05:00Z",
        swapDetails = SwapDetails(amountOutFormatted = amountOut, refundedAmountFormatted = refunded),
    )

    private fun executableRequest() =
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

    private inner class FakeApi : InvestApiProvider {
        val quotes = mutableListOf<QuoteRequest>()
        var lastRequest: QuoteRequest? = null
        var quoteFailure: InvestApiException? = null
        var echoTamper: (QuoteRequest) -> QuoteRequest = { it }
        var amountInOverride: BigDecimal? = null
        var usdIn: BigDecimal? = null
        var deadline: Instant? = null
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
                        depositAddress = if (request.dry) null else DEPOSIT,
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

    private inner class FakeWallet : OfframpBridgeWallet {
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

    private class FakeCheckpoints : InvestBuyCheckpointStorageProvider {
        val items = MutableStateFlow<List<InvestBuyCheckpoint>>(emptyList())

        override fun observe(): Flow<List<InvestBuyCheckpoint>> = items

        override suspend fun add(checkpoint: InvestBuyCheckpoint) {
            items.value = items.value + checkpoint
        }

        override suspend fun remove(depositAddress: String) {
            items.value = items.value.filterNot { it.depositAddress == depositAddress }
        }
    }

    private object FakeSwapAssetProvider : SwapAssetProvider {
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

    private companion object {
        const val FRESH_UA = "u1freshaddress"
        const val DEPOSIT = "t1depositaddress"
    }
}
