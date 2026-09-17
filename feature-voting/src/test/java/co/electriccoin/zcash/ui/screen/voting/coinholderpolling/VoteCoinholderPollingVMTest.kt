package co.electriccoin.zcash.ui.screen.voting.coinholderpolling

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.configuration.model.map.Configuration
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.voting.Proposal
import co.electriccoin.zcash.ui.common.model.voting.SessionStatus
import co.electriccoin.zcash.ui.common.model.voting.VoteOption
import co.electriccoin.zcash.ui.common.model.voting.VotingRound
import co.electriccoin.zcash.ui.common.provider.VotingApiProvider
import co.electriccoin.zcash.ui.common.repository.ConfigurationRepository
import co.electriccoin.zcash.ui.common.repository.VotingApiRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.VotingChainConfigRepository
import co.electriccoin.zcash.ui.common.repository.VotingChainConfigState
import co.electriccoin.zcash.ui.common.repository.VotingRecoveryRepository
import co.electriccoin.zcash.ui.common.repository.VotingSessionStore
import co.electriccoin.zcash.ui.common.repository.VotingSessionStoreState
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.RefreshVotingRoundsUseCase
import co.electriccoin.zcash.ui.common.usecase.RefreshVotingServiceConfigUseCase
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rounds land in [VotingApiRepositoryImpl] before the endorsement list that decides which of
 * them are visible on the default config. The "no polls" sheet must wait for the refresh to settle
 * instead of flashing in that gap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoteCoinholderPollingVMTest {
    private val apiRepository = VotingApiRepositoryImpl()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun noPollsSheetWaitsForTheRefreshToSettle() =
        runTest {
            withVm(endorsedRoundIds = setOf(ROUND_ID)) { vm ->
                vm.onScreenEntered()
                advanceTimeBy(ENDORSEMENT_DELAY_MS / 2)
                runCurrent()

                val midRefresh = vm.state.value
                assertTrue(midRefresh.isLoading)
                assertNull(midRefresh.content?.noRoundsSheet)

                settle()

                val settled = vm.state.value
                assertFalse(settled.isLoading)
                assertEquals(listOf(ROUND_ID), settled.content?.pastRounds?.map { card -> card.roundId })
                assertNull(settled.content?.noRoundsSheet)
            }
        }

    @Test
    fun noPollsSheetShowsOnceAnIdleListIsEmpty() =
        runTest {
            withVm(endorsedRoundIds = emptySet()) { vm ->
                vm.onScreenEntered()
                settle()

                val settled = vm.state.value
                assertFalse(settled.isLoading)
                assertEquals(emptyList(), settled.content?.pastRounds)
                assertNotNull(settled.content?.noRoundsSheet)
            }
        }

    private fun TestScope.settle() {
        advanceTimeBy(ENDORSEMENT_DELAY_MS)
        runCurrent()
    }

    /**
     * The VM's foreground share tracking re-arms a delay forever; if it outlives a failed
     * assertion, [runTest] drains that clock without end instead of reporting the failure.
     */
    private fun TestScope.withVm(
        endorsedRoundIds: Set<String>,
        block: TestScope.(VoteCoinholderPollingVM) -> Unit
    ) {
        val vm = vm(endorsedRoundIds)
        val collectJob = launch { vm.state.collect {} }
        try {
            block(vm)
        } finally {
            collectJob.cancel()
            vm.viewModelScope.cancel()
        }
    }

    private fun vm(endorsedRoundIds: Set<String>): VoteCoinholderPollingVM {
        val refreshVotingRounds =
            mockk<RefreshVotingRoundsUseCase> {
                coEvery { this@mockk.invoke() } coAnswers {
                    apiRepository.storeRounds(listOf(round()))
                    delay(ENDORSEMENT_DELAY_MS)
                    apiRepository.storeZodlEndorsedRoundIds(endorsedRoundIds)
                }
            }
        val account =
            mockk<WalletAccount> {
                every { sdkAccount.accountUuid } returns AccountUuid.new(ByteArray(ACCOUNT_UUID_BYTES))
            }
        return VoteCoinholderPollingVM(
            refreshVotingServiceConfig =
                mockk<RefreshVotingServiceConfigUseCase> { coEvery { this@mockk.invoke() } just Runs },
            refreshVotingRounds = refreshVotingRounds,
            configurationRepository =
                mockk<ConfigurationRepository> {
                    every { configurationFlow } returns MutableStateFlow<Configuration?>(null)
                },
            votingChainConfigRepository =
                mockk<VotingChainConfigRepository> {
                    every { state } returns MutableStateFlow(VotingChainConfigState())
                },
            votingConfigRepository = mockk(relaxed = true),
            votingApiProvider = mockk<VotingApiProvider> { coEvery { invalidateConfigCache() } just Runs },
            votingApiRepository = apiRepository,
            votingRecoveryRepository =
                mockk<VotingRecoveryRepository> {
                    coEvery { getRoundIdsRequiringShareTracking(any()) } returns emptyList()
                    coEvery { get(any(), any()) } returns null
                },
            votingSessionStore =
                mockk<VotingSessionStore> {
                    every { state } returns MutableStateFlow(VotingSessionStoreState())
                    every { clear() } just Runs
                },
            navigationRouter = mockk<NavigationRouter>(relaxed = true),
            errorStateMapper = mockk(relaxed = true),
            trackVotingShares = mockk(relaxed = true),
            observeSelectedWalletAccount =
                mockk<ObserveSelectedWalletAccountUseCase> { every { require() } returns flowOf(account) },
        )
    }

    private fun round() =
        VotingRound(
            id = ROUND_ID,
            title = "Round",
            description = "",
            discussionUrl = null,
            snapshotHeight = 1L,
            snapshotDate = Instant.EPOCH,
            votingStart = Instant.EPOCH,
            votingEnd = Instant.EPOCH,
            proposals =
                listOf(
                    Proposal(
                        id = 1,
                        title = "Proposal",
                        description = "",
                        options = listOf(VoteOption(id = 0, label = "Yes"), VoteOption(id = 1, label = "No"))
                    )
                ),
            status = SessionStatus.COMPLETED
        )

    private companion object {
        const val ROUND_ID = "16eef7ebc77e0e04fb1c7329abfcc390f4a0c002964671ebb360914a3e5a3f11"
        const val ENDORSEMENT_DELAY_MS = 1_000L
        const val ACCOUNT_UUID_BYTES = 16
    }
}
