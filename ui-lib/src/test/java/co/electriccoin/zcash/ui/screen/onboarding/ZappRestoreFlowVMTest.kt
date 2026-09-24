package co.electriccoin.zcash.ui.screen.onboarding

import android.app.Application
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.fixture.SeedPhraseFixture
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
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
class ZappRestoreFlowVMTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pasting a phrase into any field fills all 24 in order and validates the seed`() =
        runTest {
            val vm =
                ZappRestoreFlowVM(
                    application = mockk<Application>(relaxed = true),
                    validateSeed = ValidateSeedUseCase(),
                    restoreWallet = mockk(relaxed = true),
                    isKeepScreenOnDuringRestoreProvider = mockk(relaxed = true),
                )
            try {
                val field = vm.seedFieldState.value.values[3]
                field.onValueChange(SeedWordInnerTextFieldState(SeedPhraseFixture.SEED_PHRASE))

                inRealTime { vm.validSeed.first { it != null } }
                assertEquals(
                    SeedPhraseFixture.SEED_PHRASE.split(" "),
                    vm.seedFieldState.value.values
                        .map { it.innerState.value }
                )
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
