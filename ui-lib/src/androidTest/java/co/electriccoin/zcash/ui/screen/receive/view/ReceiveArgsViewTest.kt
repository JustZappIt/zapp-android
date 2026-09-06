package co.electriccoin.zcash.ui.screen.receive.view

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.filters.MediumTest
import cash.z.ecc.android.sdk.fixture.WalletAddressFixture
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

// TODO [#1184]: Improve ReceiveScreen UI tests
// TODO [#1184]: https://github.com/Electric-Coin-Company/zashi-android/issues/1184

/*
 * Note: It is difficult to test the QR code from automated tests.  There is a manual test case
 * for that currently.  A future enhancement could take a screenshot and try to analyze the
 * screenshot contents.
 */
class ReceiveArgsViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @MediumTest
    fun setup() =
        runTest {
            newTestSetup()

            // Enable substring for ellipsizing
            composeTestRule
                .onNodeWithText(
                    text = "${WalletAddressFixture.UNIFIED_ADDRESS_STRING.take(20)}...",
                    substring = true,
                    useUnmergedTree = true
                ).assertExists()
        }


    private fun newTestSetup() = ReceiveViewTestSetup(composeTestRule)
}
