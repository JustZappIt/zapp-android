package co.electriccoin.zcash.ui.screen.onboarding

import android.app.Application
import androidx.lifecycle.ViewModelStore
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Zapp onboarding restore has its own view model behind the shared seed grid, so it has to
 * spread a pasted phrase over the fields the same way the Zashi restore screen does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZappRestoreFlowVMTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pasting a valid phrase into a field spreads it over all 24 and validates the seed`() =
        restoreFlow {
            onWordChange(3, VALID_SEED)

            val fields = seedFieldsWhere { fields -> fields.all { it.isNotBlank() } }
            assertEquals(VALID_SEED.split(" "), fields)
            val seed = withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { validSeed.first { it != null } } }
            assertNotNull(seed)
        }

    @Test
    fun `pasting a phrase with a typo spreads it and leaves the seed invalid`() =
        restoreFlow {
            val words = VALID_SEED.split(" ").toMutableList().also { it[7] = "abandn" }

            onWordChange(0, words.joinToString("\n"))

            assertEquals(words, seedFieldsWhere { fields -> fields.all { it.isNotBlank() } })
            val flagged =
                withContext(Dispatchers.Default) {
                    withTimeout(TIMEOUT_MS) { seedFieldState.first { state -> state.values[7].isError } }
                }
            assertEquals(
                listOf(7),
                flagged.values
                    .withIndex()
                    .filter { it.value.isError }
                    .map { it.index }
            )
            assertNull(validSeed.value)
        }

    @Test
    fun `a single pasted word behaves like typing`() =
        restoreFlow {
            onWordChange(4, " abandon ")

            val fields = seedFieldsWhere { fields -> fields[4].isNotBlank() }
            assertEquals("abandon", fields[4])
            assertEquals(23, fields.count { it.isBlank() })
        }

    private fun restoreFlow(block: suspend ZappRestoreFlowVM.() -> Unit) =
        runTest {
            val store = ViewModelStore()
            val vm =
                ZappRestoreFlowVM(
                    application = mockk<Application>(relaxed = true),
                    validateSeed = ValidateSeedUseCase(),
                    restoreWallet = mockk(relaxed = true),
                    isKeepScreenOnDuringRestoreProvider = mockk(relaxed = true),
                )
            store.put("vm", vm)
            try {
                vm.block()
            } finally {
                store.clear()
            }
        }

    /** Change a field the way the text field does: through the callback carried in the state. */
    private fun ZappRestoreFlowVM.onWordChange(index: Int, text: String) {
        seedFieldState.value.values[index].onValueChange(SeedWordInnerTextFieldState(text))
    }

    /** Validation runs on real background dispatchers, so wait in real time, not virtual. */
    private suspend fun ZappRestoreFlowVM.seedFieldsWhere(predicate: (List<String>) -> Boolean): List<String> =
        withContext(Dispatchers.Default) {
            withTimeout(TIMEOUT_MS) {
                seedFieldState
                    .first { state -> predicate(state.values.map { it.innerState.value }) }
                    .values
                    .map { it.innerState.value }
            }
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val VALID_SEED =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
    }
}
