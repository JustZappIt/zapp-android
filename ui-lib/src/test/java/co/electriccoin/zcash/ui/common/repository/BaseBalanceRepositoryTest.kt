package co.electriccoin.zcash.ui.common.repository

import androidx.lifecycle.Lifecycle
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One reader behind every surface that shows the Base balance, so what the screens used to disagree
 * about is a single value under a single refresh policy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BaseBalanceRepositoryTest {
    private var reads = 0
    private var next: () -> Usdc6 = { ONE_USDC }

    private fun repository() =
        BaseBalanceRepositoryImpl(
            reader = {
                reads++
                next()
            },
            applicationStateProvider = FakeApplicationStateProvider(),
            dispatcher = UnconfinedTestDispatcher(),
        )

    @Test
    fun `nothing is read until something observes the balance`() =
        runTest {
            val repository = repository()
            assertEquals(0, reads)
            assertEquals(BaseBalance.Loading, repository.balance.value)
        }

    @Test
    fun `a failed read keeps the amount already on screen`() =
        runTest {
            val repository = repository()
            repository.refresh()
            next = { error("rpc down") }
            repository.refresh()
            assertEquals(ONE_USDC, repository.balance.value.loadedOrNull)
        }

    @Test
    fun `a first read that fails is unavailable rather than a zero balance`() =
        runTest {
            next = { error("rpc down") }
            val repository = repository()
            repository.refresh()
            assertEquals(BaseBalance.Unavailable, repository.balance.value)
        }

    @Test
    fun `invalidating costs nothing while no screen shows the balance`() =
        runTest {
            val repository = repository()
            repository.invalidate()
            assertEquals(0, reads)
        }

    @Test
    fun `a wipe drops the previous wallet's amount`() =
        runTest {
            val repository = repository()
            repository.refresh()
            repository.reset()
            assertEquals(BaseBalance.Loading, repository.balance.value)
        }

    private companion object {
        val ONE_USDC: Usdc6 = Usdc6.ofMicros(1_000_000L)
    }
}

private class FakeApplicationStateProvider : ApplicationStateProvider {
    override val isInForeground: Flow<Boolean> = MutableStateFlow(true)

    override fun onThirdPartyUiShown() = Unit

    override fun onApplicationLifecycleChanged(event: Lifecycle.Event) = Unit

    override fun observeOnForeground(): Flow<Unit> = emptyFlow()
}
