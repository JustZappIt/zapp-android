package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.onramp.OnrampArgs
import co.electriccoin.zcash.ui.screen.reputation.ReputationArgs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.account.OfframpSmartAccount
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reputation.ReputationReader
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.RpPerUsdcLimit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gate in front of the amount screen opens on the Diamond's buy limit: a wallet with one buys,
 * a wallet without is sent to Reputation. An unreadable chain lets the buy proceed: the failure is
 * ours.
 */
class NavigateToOnrampUseCaseTest {
    private val forwarded = mutableListOf<Any>()
    private val router =
        mockk<NavigationRouter>(relaxed = true) {
            every { forward(*anyVararg()) } answers {
                (args[0] as Array<*>).filterNotNull().forEach { forwarded.add(it) }
            }
        }
    private val accountProvider =
        mockk<SmartOfframpAccountProvider> {
            coEvery { resolve() } returns OfframpSmartAccount(owner = mockk(), address = WALLET)
        }
    private val resolveCorridor =
        mockk<ResolveBuyCorridorUseCase> {
            coEvery { this@mockk.invoke() } returns CurrencyCode.Inr
        }

    @Test
    fun aBuyLimitOpensTheAmountScreen() =
        runTest {
            useCase(buyLimit(50_000_000L)).invoke()

            assertEquals(OnrampArgs(currencyCode = "INR"), forwarded.single())
        }

    @Test
    fun noBuyLimitSendsTheWalletToReputation() =
        runTest {
            useCase(buyLimit(0L)).invoke()

            assertEquals(ReputationArgs(currency = CurrencyCode.Inr), forwarded.single())
        }

    @Test
    fun anUnreadableChainLetsTheBuyProceed() =
        runTest {
            val reader =
                mockk<ReputationReader> {
                    coEvery { read(any(), any()) } throws IllegalStateException("rpc down")
                }

            useCase(reader).invoke()

            assertEquals(OnrampArgs(currencyCode = "INR"), forwarded.single())
        }

    private fun buyLimit(micros: Long): ReputationReader =
        mockk {
            coEvery { read(WALLET, CurrencyCode.Inr) } returns
                ReputationSummary(
                    currency = CurrencyCode.Inr,
                    points = bigIntegerValueOf(0L),
                    isBlacklisted = false,
                    verified = emptySet(),
                    awards = emptyMap(),
                    buyLimit = Usdc6.ofMicros(micros),
                    maxBuyLimit = Usdc6.ofMicros(MAX_BUY_LIMIT),
                    rpPerUsdc = RpPerUsdcLimit(bigIntegerValueOf(1L), bigIntegerValueOf(1L)),
                )
        }

    private fun useCase(reader: ReputationReader) =
        NavigateToOnrampUseCase(
            resolveBuyCorridor = resolveCorridor,
            accountProvider = accountProvider,
            reputationReader = reader,
            navigateToReputation = NavigateToReputationUseCase(router),
            navigationRouter = router,
        )

    private companion object {
        val WALLET: Address = Address.parse("0x448f857ea117138e85d062c6ce89e90a337874d6")
        const val MAX_BUY_LIMIT = 500_000_000L
    }
}
