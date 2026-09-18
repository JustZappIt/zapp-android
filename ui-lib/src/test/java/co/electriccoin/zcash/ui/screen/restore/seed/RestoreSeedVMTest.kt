package co.electriccoin.zcash.ui.screen.restore.seed

import androidx.lifecycle.ViewModelStore
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
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
 * Pasting a backed-up phrase is the common way to restore, and a paste arrives as one text change
 * on whichever field the user long-pressed. These pin down how that text is split and where the
 * words land.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreSeedVMTest {
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
    fun `whitespace commas and numbering all separate pasted words`() {
        assertEquals(
            listOf("abandon", "ability", "able", "about"),
            splitPastedSeedWords("abandon ability\table\nabout")
        )
        assertEquals(
            listOf("abandon", "ability", "able"),
            splitPastedSeedWords("abandon, ability; able")
        )
        assertEquals(
            listOf("abandon", "ability", "able"),
            splitPastedSeedWords("1. abandon 2) ability 3 able")
        )
        assertEquals(
            listOf("abandon", "ability"),
            splitPastedSeedWords("  Abandon   ABILITY  ")
        )
        assertEquals(emptyList(), splitPastedSeedWords("   "))
    }

    @Test
    fun `a whole phrase fills the grid from the first field no matter where it is pasted`() {
        val current = List(24) { "" }
        val words = (1..24).map { "w$it" }

        assertEquals(words, placePastedSeedWords(current, index = 0, words = words))
        assertEquals(words, placePastedSeedWords(current, index = 17, words = words))
        // Anything beyond the grid is dropped rather than wrapped around.
        assertEquals(words, placePastedSeedWords(current, index = 5, words = words + "extra"))
    }

    @Test
    fun `a partial phrase lands on the focused field and stops at the last one`() {
        val current = List(24) { "old$it" }

        val fromMiddle = placePastedSeedWords(current, index = 3, words = listOf("a", "b", "c"))
        assertEquals(listOf("old2", "a", "b", "c", "old6"), fromMiddle.subList(2, 7))

        val nearEnd = placePastedSeedWords(current, index = 22, words = listOf("a", "b", "c"))
        assertEquals(listOf("old21", "a", "b"), nearEnd.subList(21, 24))
    }

    @Test
    fun `pasting a valid phrase into a field spreads it over all 24 and enables restore`() =
        restoreSeed {
            onWordChange(0, VALID_SEED)

            val state = stateWhere { it.seed.values.all { w -> w.innerState.value.isNotBlank() } }
            assertEquals(VALID_SEED.split(" "), state.seed.values.map { it.innerState.value })
            assertEquals(List(24) { false }, state.seed.values.map { it.isError })
            assertNotNull(state.nextButton)
        }

    @Test
    fun `pasting a phrase with a typo still spreads it and flags only the bad word`() =
        restoreSeed {
            val words = VALID_SEED.split(" ").toMutableList().also { it[7] = "abandn" }

            onWordChange(11, words.joinToString("\n"))

            val state = stateWhere { it.seed.values[7].isError }
            assertEquals(words, state.seed.values.map { it.innerState.value })
            assertEquals(
                listOf(7),
                state.seed.values
                    .withIndex()
                    .filter { it.value.isError }
                    .map { it.index }
            )
            assertNull(state.nextButton)
        }

    @Test
    fun `a single pasted word behaves like typing`() =
        restoreSeed {
            onWordChange(4, " abandon ")

            val state =
                stateWhere {
                    it.seed.values[4]
                        .innerState.value
                        .isNotBlank()
                }
            assertEquals(
                "abandon",
                state.seed.values[4]
                    .innerState.value
            )
            assertEquals(23, state.seed.values.count { it.innerState.value.isBlank() })
        }

    /**
     * Runs [block] against a fresh view model and clears it afterwards, so its eager flows do not
     * outlive the test's Main dispatcher.
     */
    private fun restoreSeed(block: suspend RestoreSeedVM.() -> Unit) =
        runTest {
            val store = ViewModelStore()
            val vm = RestoreSeedVM(mockk(relaxed = true), ValidateSeedUseCase())
            store.put("vm", vm)
            try {
                vm.block()
            } finally {
                store.clear()
            }
        }

    /** Change a field the way the text field does: through the callback carried in the state. */
    private suspend fun RestoreSeedVM.onWordChange(
        index: Int,
        text: String
    ) {
        val field = stateWhere { true }.seed.values[index]
        field.onValueChange(SeedWordInnerTextFieldState(text))
    }

    /** The view model validates on real background dispatchers, so wait in real time, not virtual. */
    private suspend fun RestoreSeedVM.stateWhere(predicate: (RestoreSeedState) -> Boolean): RestoreSeedState =
        withContext(Dispatchers.Default) {
            withTimeout(STATE_TIMEOUT_MS) { state.filterNotNull().first(predicate) }
        }

    private companion object {
        const val STATE_TIMEOUT_MS = 5_000L
        const val VALID_SEED =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
    }
}
