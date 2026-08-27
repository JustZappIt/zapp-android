package co.electriccoin.zcash.ui.common.usecase

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.screen.error.ErrorDialog
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteArgs
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The exact-output guard. `requestExactOutput` promises the recipient a specific amount, so the
 * quote the server hands back has to deliver exactly that: anything else is thrown away rather
 * than shown for confirmation. The comparison itself is covered by NearSwapQuoteValidationTest;
 * what is pinned here is that the exact-output branch feeds it the *destination* leg, and what the
 * use case does with the verdict.
 *
 * Proposal construction needs a live Synchronizer and is out of scope, so "the quote was accepted"
 * is observed as the use case going on to ask for one — see [ExactOutputSynchronizerProviderFake].
 *
 * Hand-written fakes throughout: this path suspends on the quote flow from `Dispatchers.Default`,
 * so a stub that fails to publish would hang the test rather than fail it.
 */
class RequestExactOutputQuoteUseCaseTest {
    private val router = ExactOutputNavigationRouterFake()
    private val accounts = ExactOutputAccountDataSourceFake()
    private val synchronizers = ExactOutputSynchronizerProviderFake()

    @Test
    fun `a quote that would deliver less than the requested amount is rejected`() =
        runTest {
            val swapRepository = repositoryQuoting(amountOutFormatted = BigDecimal("0.0009"))

            useCase(swapRepository).requestExactOutput(REQUESTED, DESTINATION) { true }

            assertEquals(listOf<Any>(ErrorDialog), router.forwarded)
            assertFalse(router.forwarded.contains(SwapQuoteArgs))
            assertFalse(synchronizers.wasAsked, "a rejected quote must not reach proposal building")
            assertTrue(swapRepository.quoteCleared)
        }

    @Test
    fun `a quote that would deliver more than the requested amount is also rejected`() =
        runTest {
            // Over-delivering is still not the quote we asked for, so it fails closed too.
            val swapRepository = repositoryQuoting(amountOutFormatted = BigDecimal("0.0011"))

            useCase(swapRepository).requestExactOutput(REQUESTED, DESTINATION) { true }

            assertEquals(listOf<Any>(ErrorDialog), router.forwarded)
            assertFalse(synchronizers.wasAsked)
            assertTrue(swapRepository.quoteCleared)
        }

    @Test
    fun `a quote for the requested amount is accepted whatever scale it is expressed at`() =
        runTest {
            val swapRepository = repositoryQuoting(amountOutFormatted = BigDecimal("0.00100000"))

            useCase(swapRepository).requestExactOutput(REQUESTED, DESTINATION) { true }

            assertTrue(synchronizers.wasAsked, "the quote matched, so a proposal should be built")
        }

    @Test
    fun `the quote is requested for the user's amount, the recipient, and a fresh refund address`() =
        runTest {
            val swapRepository = repositoryQuoting(amountOutFormatted = BigDecimal("0.001"))

            useCase(swapRepository).requestExactOutput(REQUESTED, DESTINATION) { true }

            assertEquals(REQUESTED, swapRepository.requestedAmount)
            assertEquals(DESTINATION, swapRepository.requestedAddress)
            // A per-quote shielded address, so NEAR's refund of the unused slippage buffer never
            // lands on one the user has already handed out.
            assertEquals(accounts.lastIssuedAddress, swapRepository.requestedRefundAddress)
        }

    private fun useCase(swapRepository: SwapRepository) =
        RequestSwapQuoteUseCase(
            navigationRouter = router,
            navigateToErrorUseCase = NavigateToErrorUseCase(router),
            swapRepository = swapRepository,
            zashiProposalRepository = ExactOutputZashiProposalsFake,
            keystoneProposalRepository = ExactOutputKeystoneProposalsFake,
            accountDataSource = accounts,
            synchronizerProvider = synchronizers,
        )

    private fun repositoryQuoting(amountOutFormatted: BigDecimal) =
        ExactOutputSwapRepositoryFake(ExactOutputQuoteFake(amountOutFormatted))

    private companion object {
        val REQUESTED: BigDecimal = BigDecimal("0.001")
        const val DESTINATION = "bc1qexampledestination"
    }
}

private object ExactOutputTestAssets {
    val zec = asset("ZEC", "ZEC")
    val btc = asset("BTC", "BTC")

    private fun asset(token: String, chain: String) =
        DynamicSwapAsset(
            tokenTicker = token,
            tokenName = StringResource.ByString(token),
            tokenIcon = imageRes(token),
            usdPrice = null,
            assetId = "$token.$chain",
            decimals = 8,
            blockchain =
                SwapBlockchain(
                    chainTicker = chain,
                    chainName = StringResource.ByString(chain),
                    chainIcon = imageRes(chain),
                ),
        )
}

/** Answers an exact-output request immediately with [readyQuote], the way a served quote arrives. */
private class ExactOutputSwapRepositoryFake(
    private val readyQuote: ExactOutputQuoteFake
) : SwapRepository {
    var requestedAmount: BigDecimal? = null
        private set
    var requestedAddress: String? = null
        private set
    var requestedRefundAddress: String? = null
        private set
    var quoteCleared = false
        private set

    override val assets = MutableStateFlow(SwapAssetsData(zecAsset = ExactOutputTestAssets.zec))
    override val selectedAsset = MutableStateFlow<SwapAsset?>(ExactOutputTestAssets.btc)
    override val slippage = MutableStateFlow(BigDecimal.ONE)
    override val quote = MutableStateFlow<SwapQuoteData?>(null)

    override fun requestExactOutputQuote(amount: BigDecimal, address: String, refundAddress: String) {
        requestedAmount = amount
        requestedAddress = address
        requestedRefundAddress = refundAddress
        quote.value = SwapQuoteData.Success(readyQuote)
    }

    override fun clearQuote() {
        quoteCleared = true
        quote.value = null
    }

    override fun select(asset: SwapAsset?) = Unit

    override fun setSlippage(amount: BigDecimal) = Unit

    override fun requestRefreshAssets() = Unit

    override suspend fun requestRefreshAssetsOnce() = Unit

    override fun requestExactInputQuote(amount: BigDecimal, address: String, refundAddress: String) = Unit

    override fun requestExactInputIntoZec(amount: BigDecimal, refundAddress: String, destinationAddress: String) = Unit

    override fun clear() = Unit
}

private class ExactOutputQuoteFake(
    override val amountOutFormatted: BigDecimal
) : SwapQuote {
    override val originAsset: SwapAsset = ExactOutputTestAssets.zec
    override val destinationAsset: SwapAsset = ExactOutputTestAssets.btc
    override val depositAddress: SwapAddress = DynamicSwapAddress("u1depositaddressforunittests")
    override val destinationAddress: SwapAddress = DynamicSwapAddress("bc1qexampledestination")
    override val refundAddress: SwapAddress = DynamicSwapAddress(ExactOutputAccountDataSourceFake.ADDRESS)
    override val provider: String = "near"
    override val mode: SwapMode = SwapMode.EXACT_OUTPUT
    override val zecExchangeRate: BigDecimal = BigDecimal.ONE

    // One whole ZEC in zatoshi — the use case requires an exact zatoshi value here.
    override val amountIn: BigDecimal = BigDecimal("100000000")
    override val amountInFormatted: BigDecimal = BigDecimal.ONE
    override val amountInUsd: BigDecimal = BigDecimal("50")

    override val amountOut: BigDecimal = BigDecimal("100000")
    override val amountOutUsd: BigDecimal = BigDecimal("50")

    override val affiliateFee: BigDecimal = BigDecimal.ZERO
    override val affiliateFeeZatoshi: Zatoshi = Zatoshi(0)
    override val affiliateFeeUsd: BigDecimal = BigDecimal.ZERO

    override val timestamp: Instant = Instant.fromEpochSeconds(0)
    override val deadline: Instant = Instant.fromEpochSeconds(0)
    override val estimatedDurationSeconds: Int? = null
    override val slippage: BigDecimal = BigDecimal.ONE

    override fun getTotal(proposal: Proposal?): BigDecimal = amountIn

    override fun getTotalUsd(proposal: Proposal?): BigDecimal = amountInUsd

    override fun getTotalFeesUsd(proposal: Proposal?): BigDecimal = BigDecimal.ZERO

    override fun getTotalFeesZatoshi(proposal: Proposal?): Zatoshi = Zatoshi(0)
}

private class ExactOutputAccountDataSourceFake : AccountDataSource {
    var lastIssuedAddress: String? = null
        private set

    override suspend fun requestNextShieldedAddress(): WalletAddress.Unified =
        WalletAddress.Unified.new(ADDRESS).also { lastIssuedAddress = it.address }

    override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(null)
    override val selectedAccount: Flow<WalletAccount?> = emptyFlow()
    override val zashiAccount: Flow<ZashiAccount?> = emptyFlow()

    override suspend fun getAllAccounts(): List<WalletAccount> = error("unused")

    override suspend fun getSelectedAccount(): WalletAccount = error("unused")

    override suspend fun getZashiAccount(): ZashiAccount = error("unused")

    override suspend fun selectAccount(account: Account) = error("unused")

    override suspend fun selectAccount(account: WalletAccount) = error("unused")

    override suspend fun importKeystoneAccount(
        ufvk: String,
        seedFingerprint: String,
        index: Long,
        birthday: BlockHeight?
    ): Account = error("unused")

    override suspend fun deleteAccount(account: WalletAccount) = error("unused")

    companion object {
        const val ADDRESS = "u1refundaddressforunittests"
    }
}

/**
 * Proposal building starts by resolving the deposit address through the synchronizer, so being
 * asked for one is the observable signal that the quote survived validation. Throwing from here
 * keeps the rest of the proposal machinery out of the test.
 */
private class ExactOutputSynchronizerProviderFake : SynchronizerProvider {
    var wasAsked = false
        private set

    override suspend fun getSynchronizer(): Synchronizer {
        wasAsked = true
        error("proposal building is out of scope for this test")
    }

    override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
    override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)
    override val walletBalances: Flow<Map<AccountUuid, AccountBalance>?> = emptyFlow()

    override suspend fun getSynchronizerOrNull(): Synchronizer? = null

    override suspend fun getVotingWalletDbPath(): String = error("voting is out of scope for this test")

    override fun resetSynchronizer() = Unit
}

private object ExactOutputZashiProposalsFake : ZashiProposalRepository {
    override val transactionProposal: StateFlow<TransactionProposal?> = MutableStateFlow(null)
    override val submitState: StateFlow<SubmitProposalState?> = MutableStateFlow(null)

    override suspend fun createProposal(zecSend: ZecSend): RegularTransactionProposal = error("unused")

    override suspend fun createZip321Proposal(zip321Uri: String): Zip321TransactionProposal = error("unused")

    override suspend fun createExactInputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote
    ): ExactInputSwapTransactionProposal = error("unused")

    override suspend fun createExactOutputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote
    ): ExactOutputSwapTransactionProposal = error("unused")

    override suspend fun createShieldProposal() = error("unused")

    override fun setMigrationSweepProposal(proposal: Proposal, amount: Zatoshi) = error("unused")

    override suspend fun submit(): SubmitResult = error("unused")

    override suspend fun getTransactionProposal(): TransactionProposal = error("unused")

    override fun clear() = Unit
}

private object ExactOutputKeystoneProposalsFake : KeystoneProposalRepository {
    override val transactionProposal: StateFlow<TransactionProposal?> = MutableStateFlow(null)
    override val submitState: StateFlow<SubmitProposalState?> = MutableStateFlow(null)

    override suspend fun createProposal(zecSend: ZecSend) = error("unused")

    override suspend fun createExactInputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote
    ): ExactInputSwapTransactionProposal = error("unused")

    override suspend fun createExactOutputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote
    ): ExactOutputSwapTransactionProposal = error("unused")

    override suspend fun createZip321Proposal(zip321Uri: String): Zip321TransactionProposal = error("unused")

    override suspend fun createShieldProposal() = error("unused")

    override fun setMigrationSweepProposal(proposal: Proposal, amount: Zatoshi) = error("unused")

    override suspend fun createPCZTFromProposal() = error("unused")

    override suspend fun createPCZTEncoder(): UREncoder = error("unused")

    override suspend fun parsePCZT(ur: UR) = error("unused")

    override suspend fun submit(): SubmitResult = error("unused")

    override fun clear() = Unit

    override suspend fun getTransactionProposal(): TransactionProposal = error("unused")

    override fun getProposalPCZT(): Pczt? = null
}

private class ExactOutputNavigationRouterFake : NavigationRouter {
    val forwarded = mutableListOf<Any>()

    override fun forward(vararg routes: Any) {
        forwarded.addAll(routes)
    }

    override fun replace(vararg routes: Any) = Unit

    override fun replaceAll(vararg routes: Any) = Unit

    override fun back() = Unit

    override fun backTo(route: KClass<*>) = Unit

    override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

    override fun backToRoot() = Unit

    override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
}
