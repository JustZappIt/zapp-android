package co.electriccoin.zcash.ui.screen.restore.seed

import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.fixture.SeedPhraseFixture
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreSeedVMTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pasting a phrase into any field fills all 24 in order and enables restore`() =
        runTest {
            val vm = RestoreSeedVM(mockk(relaxed = true), ValidateSeedUseCase())
            try {
                val field = inRealTime { vm.state.filterNotNull().first() }.seed.values[5]
                field.onValueChange(SeedWordInnerTextFieldState(SeedPhraseFixture.SEED_PHRASE))

                val state = inRealTime { vm.state.filterNotNull().first { it.nextButton != null } }
                assertEquals(SeedPhraseFixture.SEED_PHRASE.split(" "), state.seed.values.map { it.innerState.value })
            } finally {
                // Let in-flight validation finish before Main is reset, or it fails the next test class.
                vm.viewModelScope.coroutineContext.job
                    .cancelAndJoin()
            }
        }

    // Validation runs on a real background dispatcher, so wait in real time.
    private suspend fun <T> inRealTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { block() } }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
