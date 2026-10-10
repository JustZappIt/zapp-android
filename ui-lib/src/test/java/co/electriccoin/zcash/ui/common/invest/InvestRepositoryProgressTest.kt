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
internal class InvestRepositoryProgressTest : InvestRepositoryImplTestBase() {
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
    fun `the market lists the curated stocks 1Click still lists, priced or not`() =
        runTest {
            repository.refreshMarket()

            val market = requireNotNull(repository.market.value)
            // The fake lists NVDA (priced) and TSLA (no price); the other curated stocks aren't listed.
            assertEquals(listOf(nvda, InvestAssets.curated[1]), market.assets.map { it.asset })
            assertEquals(BigDecimal("224.46"), market.assets.first { it.asset == nvda }.usdPrice)
            assertNull(market.assets.first { it.asset == InvestAssets.curated[1] }.usdPrice)
            assertEquals(2, repository.investSwapAssets().size)
        }

    @Test
    fun `holdings count one row per stock from the private balance only`() =
        runTest {
            coEvery { session.balances() } returns
                BalancesResponse(
                    listOf(
                        AccountBalance(tokenId = nvda.assetId, available = "400000000000000000", source = "private"),
                        AccountBalance(tokenId = nvda.assetId, available = "40974000000000000", source = null),
                        AccountBalance(tokenId = nvda.assetId, available = "900000000000000000", source = "public"),
                    ),
                )

            repository.refreshHoldings()

            val holding = requireNotNull(repository.holdings.value).items.single()
            assertEquals(BigDecimal("0.440974"), holding.units)
        }

    @Test
    fun `a wallet reset forgets the holdings and the estimate's refund address`() =
        runTest {
            coEvery { session.balances() } returns
                BalancesResponse(listOf(AccountBalance(nvda.assetId, "440974000000000000", "private")))
            repository.refreshHoldings()
            repository.estimateBuy(nvda, BigDecimal(100))
            assertEquals(1, wallet.addressesHandedOut)

            repository.clearWalletData()

            assertNull(repository.holdings.value)
            repository.estimateBuy(nvda, BigDecimal(100))
            assertEquals(2, wallet.addressesHandedOut) // the next wallet's estimate asks for its own address
        }

    @Test
    fun `a receipt for an old buy doesn't read the private balance, a followed buy does`() =
        runTest {
            coEvery { session.balances() } returns BalancesResponse(emptyList())
            api.statuses += status(SwapStatus.SUCCESS, amountOut = BigDecimal("0.440974"))

            repository.observeBuy(DEPOSIT).toList()
            coVerify(exactly = 0) { session.balances() }

            checkpoints.add(InvestBuyCheckpoint(DEPOSIT, nvda.assetId, 0))
            api.statuses += status(SwapStatus.SUCCESS, amountOut = BigDecimal("0.440974"))
            repository.observeBuy(DEPOSIT).toList()
            coVerify(exactly = 1) { session.balances() }
        }
}
