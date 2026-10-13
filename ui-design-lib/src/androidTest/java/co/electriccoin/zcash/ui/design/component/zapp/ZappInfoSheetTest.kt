package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class ZappInfoSheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val ok = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.general_ok)

    @Test
    @MediumTest
    fun okCanBeReachedWhenTheTextIsTallerThanTheScreen() {
        var dismissals = 0
        composeTestRule.setContent {
            ZcashTheme {
                ZappInfoSheet(
                    title = TITLE,
                    steps = List(STEPS) { "Step $it: $LONG_TEXT" },
                    notes = List(NOTES) { "Note $it: $LONG_TEXT" },
                    onDismiss = { dismissals++ },
                )
            }
        }

        composeTestRule.onNode(hasText(TITLE) and isHeading()).assertExists()
        composeTestRule
            .onNodeWithText(ok)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, dismissals)
    }

    private companion object {
        const val TITLE = "How this works"
        const val STEPS = 20
        const val NOTES = 6
        const val LONG_TEXT =
            "a sentence long enough to wrap onto a few lines, so that all of them together are " +
                "far taller than any phone's screen, even before the font is made any larger."
    }
}
