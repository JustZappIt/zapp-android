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
internal class InvestRepositoryImplTest : InvestRepositoryImplTestBase() {
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
            api.depositAddress = "t1seconddeposit"
            val second = repository.prepareBuy(nvda, BigDecimal(100))
            wallet.sendFailure = IllegalStateException("ZEC bridge deposit did not succeed: Partial")

            assertFailsWith<IllegalStateException> { repository.executeBuy(prepared) }

            assertEquals(listOf(DEPOSIT), checkpoints.items.value.map { it.depositAddress })

            // Its outcome is unknown, so the same stock can't be paid for again until it settles, not even
            // with another quote prepared before it failed.
            wallet.sendFailure = null
            wallet.checkpointsAtSend = null
            assertFailsWith<IllegalStateException> { repository.prepareBuy(nvda, BigDecimal(100)) }
            assertFailsWith<IllegalStateException> { repository.executeBuy(second) }
            assertNull(wallet.checkpointsAtSend)
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
    fun `pending trades list buys and sales by stock`() =
        runTest {
            repository.executeBuy(repository.prepareBuy(nvda, BigDecimal(100)))
            sellCheckpoints.value = listOf(InvestBuyCheckpoint("sell-deposit", "nep141:other", 0))

            assertEquals(
                listOf(PendingTrade(DEPOSIT, nvda.assetId, isSale = false), PendingTrade("sell-deposit", "nep141:other", isSale = true)),
                repository.pendingTrades.first(),
            )
        }

    @Test
    fun `after moving where Invest isn't offered, buying stops`() =
        runTest {
            val prepared = repository.prepareBuy(nvda, BigDecimal(100))
            residence = residence.copy(countryCode = "CA")

            assertFailsWith<IllegalStateException> { repository.prepareBuy(nvda, BigDecimal(100)) }
            assertFailsWith<IllegalStateException> { repository.executeBuy(prepared) }
            assertNull(wallet.checkpointsAtSend)

            // A restricted country is fine once the user has attested to being a qualified investor.
            residence = InvestSettings(countryCode = "SG", qualifiedInvestor = true, setupComplete = true)
            repository.prepareBuy(nvda, BigDecimal(100))
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
}
