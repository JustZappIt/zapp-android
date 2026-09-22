package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.onramp.OnrampArgs
import co.electriccoin.zcash.ui.screen.reputation.ReputationArgs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.account.OfframpSmartAccount
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.onramp.OnrampRouteLimits
import xyz.justzappit.offramp.onramp.OnrampRouteReader
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gate in front of the amount screen opens on the higher of the two per-order limits — so a
 * wallet with no reputation but a passed selfie check buys, and one with neither is sent to
 * Reputation. An unreadable chain lets the buy proceed: the failure is ours.
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
    fun aSelfieLimitAloneOpensTheAmountScreen() =
        runTest {
            useCase(limits(direct = 0L, integrator = 20_000_000L)).invoke()

            assertEquals(OnrampArgs(currencyCode = "INR"), forwarded.single())
        }

    @Test
    fun aReputationLimitAloneOpensTheAmountScreen() =
        runTest {
            useCase(limits(direct = 50_000_000L, integrator = 0L)).invoke()

            assertEquals(OnrampArgs(currencyCode = "INR"), forwarded.single())
        }

    @Test
    fun neitherLimitSendsTheWalletToReputation() =
        runTest {
            useCase(limits(direct = 0L, integrator = 0L)).invoke()

            assertEquals(ReputationArgs(currency = CurrencyCode.Inr), forwarded.single())
        }

    @Test
    fun anUnreadableChainLetsTheBuyProceed() =
        runTest {
            val reader =
                mockk<OnrampRouteReader> {
                    coEvery { read(any(), any()) } throws IllegalStateException("rpc down")
                }

            useCase(reader).invoke()

            assertEquals(OnrampArgs(currencyCode = "INR"), forwarded.single())
        }

    private fun limits(direct: Long, integrator: Long): OnrampRouteReader =
        mockk {
            coEvery { read(WALLET, CurrencyCode.Inr) } returns
                OnrampRouteLimits(
                    direct = Usdc6.ofMicros(direct),
                    integrator = Usdc6.ofMicros(integrator),
                    integratorOrdersRemaining = if (integrator == 0L) bigIntegerZero else bigIntegerValueOf(5L),
                )
        }

    private fun useCase(reader: OnrampRouteReader) =
        NavigateToOnrampUseCase(
            resolveBuyCorridor = resolveCorridor,
            accountProvider = accountProvider,
            routeReader = reader,
            navigateToReputation = NavigateToReputationUseCase(router),
            navigationRouter = router,
        )

    private companion object {
        val WALLET: Address = Address.parse("0x448f857ea117138e85d062c6ce89e90a337874d6")
    }
}
