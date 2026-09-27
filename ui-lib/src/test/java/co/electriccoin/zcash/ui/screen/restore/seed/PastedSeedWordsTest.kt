package co.electriccoin.zcash.ui.screen.restore.seed

import androidx.compose.ui.text.TextRange
import cash.z.ecc.sdk.fixture.SeedPhraseFixture
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordTextFieldState
import co.electriccoin.zcash.ui.design.component.TextSelection
import org.junit.Test
import kotlin.test.assertEquals

class PastedSeedWordsTest {
    private val phrase = SeedPhraseFixture.SEED_PHRASE.split(" ")

    @Test
    fun `numbering punctuation and any whitespace separate words`() {
        assertEquals(
            listOf("abandon", "ability", "able", "about", "above"),
            splitPastedSeedWords("1. Abandon\n2)ability, able; \"about\" #5 above.")
        )
        assertEquals(emptyList(), splitPastedSeedWords(" 1. \n"))
    }

    @Test
    fun `a whole phrase fills the grid from the first field wherever it is pasted`() {
        assertEquals(phrase, emptyGrid().withPastedWords(17, phrase).values())
        assertEquals(phrase, emptyGrid().withPastedWords(5, phrase + "extra").values())
    }

    @Test
    fun `a partial paste starts at the focused field and stops at the last one`() {
        val grid = emptyGrid().withPastedWords(22, listOf("a", "b", "c"))

        assertEquals(List(22) { "" } + listOf("a", "b"), grid.values())
    }

    @Test
    fun `only the text inserted at the cursor or selection counts as pasted`() {
        val clip = SeedPhraseFixture.SEED_PHRASE

        assertEquals(phrase, pastedSeedWords(field("sti", TextSelection.End), field("sti$clip")))
        assertEquals(phrase, pastedSeedWords(field("sti", TextSelection.ByTextRange(TextRange(0, 3))), field(clip)))
    }

    @Test
    fun `typing is never a paste`() {
        val cursorMidWord = field("abandon", TextSelection.ByTextRange(TextRange(4)))

        assertEquals(emptyList(), pastedSeedWords(cursorMidWord, field("aban don")))
        assertEquals(emptyList(), pastedSeedWords(field(""), field(" abandon ")))
    }

    private fun field(
        value: String,
        selection: TextSelection = TextSelection.Start
    ) = SeedWordInnerTextFieldState(value, selection)

    private fun emptyGrid() = List(24) { SeedWordTextFieldState(field(""), isError = false, onValueChange = {}) }

    private fun List<SeedWordTextFieldState>.values() = map { it.innerState.value }
}
