package co.electriccoin.zcash.ui.screen.about.view

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.MediumTest
import androidx.test.filters.SmallTest
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.fixture.ConfigInfoFixture
import co.electriccoin.zcash.ui.fixture.VersionInfoFixture
import co.electriccoin.zcash.ui.test.getStringResource
import co.electriccoin.zcash.ui.test.getStringResourceWithArgs
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class AboutViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @MediumTest
    fun default_ui_state_test() {
        val testSetup = newTestSetup()

        assertEquals(0, testSetup.getOnBackCount())

        composeTestRule
            .onNodeWithContentDescription(
                getStringResource(co.electriccoin.zcash.ui.design.R.string.general_back_content_description),
                ignoreCase = true
            ).also {
                it.assertExists()
            }

        composeTestRule.onNodeWithText(getStringResource(R.string.about_legal_notice)).also {
            it.assertExists()
        }
    }

    @Test
    @MediumTest
    fun version_setup_test() {
        newTestSetup()

        composeTestRule
            .onNodeWithText(
                getStringResourceWithArgs(R.string.settings_version, VersionInfoFixture.VERSION_NAME)
            ).also {
                it.assertExists()
            }
    }

    @Test
    @MediumTest
    fun back_test() {
        val testSetup = newTestSetup()

        assertEquals(0, testSetup.getOnBackCount())

        composeTestRule
            .onNodeWithContentDescription(
                getStringResource(co.electriccoin.zcash.ui.design.R.string.general_back_content_description)
            ).also {
                it.performClick()
            }

        assertEquals(1, testSetup.getOnBackCount())
    }

    @Test
    @SmallTest
    fun debug_menu_visible_test() {
        newTestSetup(isDebuggable = true)

        composeTestRule.onNodeWithTag(AboutTag.DEBUG_MENU_TAG).also {
            it.assertExists()
        }
    }

    @Test
    @SmallTest
    fun debug_menu_not_visible_test() {
        newTestSetup(isDebuggable = false)

        composeTestRule.onNodeWithTag(AboutTag.DEBUG_MENU_TAG).also {
            it.assertDoesNotExist()
        }
    }

    private fun newTestSetup(isDebuggable: Boolean = false) =
        AboutViewTestSetup(
            composeTestRule,
            VersionInfoFixture.new(isDebuggable = isDebuggable),
            ConfigInfoFixture.new()
        )
}
