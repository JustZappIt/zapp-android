package co.electriccoin.zcash.ui.common.usecase

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.migration.MigrationNavigator
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.provider.ChatSendContextProvider
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.swap.upi.bridge.BridgeToBaseArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CancelProposalFlowUseCaseTest {
    @Test
    fun migrationSweepProposalNavigatesBackToMigrationReviewInsteadOfSend() =
        runTest {
            val fixture = fixture(MigrationSweepTransactionProposal(Zatoshi(500_000L), mockk<Proposal>()))

            fixture.useCase()

            coVerify(exactly = 1) { fixture.keystoneProposalRepository.clear() }
            assertEquals(0, fixture.router.backToCalls.size)
            assertEquals(1, fixture.migrationNavigator.backToReviewCalls)
        }

    @Test
    fun claimedSignReturnRouteWinsOverTheProposalShapedRoute() =
        runTest {
            val fixture =
                fixture(
                    proposal = mockk<ExactOutputSwapTransactionProposal>(relaxed = true),
                    signReturnRoute = BridgeToBaseArgs::class,
                )

            fixture.useCase()

            coVerify(exactly = 1) { fixture.keystoneProposalRepository.clear() }
            assertEquals<KClass<*>>(BridgeToBaseArgs::class, fixture.router.backToCalls.single())
            assertEquals(0, fixture.migrationNavigator.backToReviewCalls)
        }

    private class Fixture(
        val useCase: CancelProposalFlowUseCase,
        val keystoneProposalRepository: KeystoneProposalRepository,
        val router: FakeNavigationRouter,
        val migrationNavigator: FakeMigrationNavigator,
    )

    private fun fixture(proposal: TransactionProposal, signReturnRoute: KClass<*>? = null): Fixture {
        val keystoneProposalRepository =
            mockk<KeystoneProposalRepository>(relaxed = true) {
                coEvery { getTransactionProposal() } returns proposal
                every { this@mockk.signReturnRoute } returns signReturnRoute
            }
        val router = FakeNavigationRouter()
        val migrationNavigator = FakeMigrationNavigator()
        return Fixture(
            useCase =
                CancelProposalFlowUseCase(
                    zashiProposalRepository = mockk<ZashiProposalRepository>(relaxed = true),
                    keystoneProposalRepository = keystoneProposalRepository,
                    navigationRouter = router,
                    observeClearSend = mockk<ObserveClearSendUseCase>(relaxed = true),
                    accountDataSource =
                        mockk<AccountDataSource> {
                            coEvery { getSelectedAccount() } returns mockk<KeystoneAccount>(relaxed = true)
                        },
                    swapRepository = mockk<SwapRepository>(relaxed = true),
                    chatSendContext = ChatSendContextProvider(),
                    migrationNavigator = migrationNavigator,
                ),
            keystoneProposalRepository = keystoneProposalRepository,
            router = router,
            migrationNavigator = migrationNavigator,
        )
    }

    private class FakeMigrationNavigator : MigrationNavigator {
        var backToReviewCalls = 0

        override fun backToMigrationReview() {
            backToReviewCalls++
        }

        override fun forwardToRestartMigration() {
            // no-op fake
        }
    }

    private class FakeNavigationRouter : NavigationRouter {
        val backToCalls = mutableListOf<KClass<*>>()

        override fun forward(vararg routes: Any) = Unit

        override fun replace(vararg routes: Any) = Unit

        override fun replaceAll(vararg routes: Any) = Unit

        override fun back() = Unit

        override fun backTo(route: KClass<*>) {
            backToCalls += route
        }

        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }
}
