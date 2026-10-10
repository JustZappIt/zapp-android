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
internal class InvestSellProgressTest : InvestSellRepositoryTestBase() {
    @Test
    fun `progress runs to sent and refreshes holdings`() =
        runTest {
            api.statuses.addAll(listOf(status(SwapStatus.PENDING_DEPOSIT), status(SwapStatus.PROCESSING), status(SwapStatus.SUCCESS)))

            val progress = repository.observeSell(DEPOSIT).toList()

            assertEquals(
                listOf(SellProgress.Authorised(DEPOSIT), SellProgress.Selling(DEPOSIT), SellProgress.Sent(DEPOSIT, BigDecimal("0.0634"))),
                progress,
            )
            coVerify { investRepository.refreshHoldings() }
        }

    @Test
    fun `an intent past its deadline is unsold only while the stock is still held`() =
        runTest {
            checkpoints.add(pastDeadline())
            api.statuses += status(SwapStatus.PENDING_DEPOSIT)
            now += 14.minutes

            assertEquals(listOf(SellProgress.NotSold(DEPOSIT)), repository.observeSell(DEPOSIT).toList())
            assertTrue(checkpoints.items.value.isEmpty())

            // 1Click never saw it, but the stock left the account: that's for support, not "not sold".
            checkpoints.add(pastDeadline(now - 14.minutes).copy(intentHash = "hash"))
            api.statuses += status(SwapStatus.PENDING_DEPOSIT)
            holdings.value = "0"

            assertEquals(listOf(SellProgress.NeedsAttention(DEPOSIT, "hash")), repository.observeSell(DEPOSIT).toList())
            assertEquals(1, checkpoints.items.value.size)
        }

    @Test
    fun `status not knowing the sale yet is waiting until the intent's deadline`() =
        runTest {
            checkpoints.add(pastDeadline())
            api.statuses.addAll(listOf(notFound(), notFound(), notFound(), status(SwapStatus.SUCCESS)))

            assertEquals(
                List(3) { SellProgress.Authorised(DEPOSIT) } + SellProgress.Sent(DEPOSIT, BigDecimal("0.0634")),
                repository.observeSell(DEPOSIT).toList(),
            )

            // Past the deadline an unknown sale is settled by the balance, like one still pending.
            checkpoints.add(pastDeadline())
            now += 14.minutes
            api.statuses.addAll(listOf(notFound()))
            assertEquals(listOf(SellProgress.NotSold(DEPOSIT)), repository.observeSell(DEPOSIT).toList())

            // With no checkpoint there's no deadline to wait for: repeated rejections go to support.
            api.statuses.addAll(listOf(notFound(), notFound(), notFound()))
            assertEquals(listOf(SellProgress.NeedsAttention(DEPOSIT, DEPOSIT)), repository.observeSell(DEPOSIT).toList())
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
}
