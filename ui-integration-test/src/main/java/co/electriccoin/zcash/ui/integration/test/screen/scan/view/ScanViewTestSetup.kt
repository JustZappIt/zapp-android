package co.electriccoin.zcash.ui.integration.test.screen.scan.view

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiObjectNotFoundException
import androidx.test.uiautomator.UiSelector
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.integration.test.common.getPermissionNegativeButtonUiObject
import co.electriccoin.zcash.ui.integration.test.common.getPermissionPositiveButtonUiObject
import co.electriccoin.zcash.ui.integration.test.common.getStringResource
import co.electriccoin.zcash.ui.screen.scan.ScanScreenState
import co.electriccoin.zcash.ui.screen.scan.ScanTag
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scan.ScanView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal const val PERMISSION_DIALOG_TIMEOUT_MILLIS = 10_000L
private const val TAP_ATTEMPTS = 3
private const val TAP_WAIT_MILLIS = 3_000L

class ScanViewTestSetup(
    private val composeTestRule: ComposeContentTestRule
) {
    private val onOpenSettingsCount = AtomicInteger(0)
    private val scanState = AtomicReference(ScanScreenState.Permission)

    fun getOnOpenSettingsCount(): Int {
        composeTestRule.waitForIdle()
        return onOpenSettingsCount.get()
    }

    fun getScanState(): ScanScreenState {
        composeTestRule.waitForIdle()
        return scanState.get()
    }

    fun grantPermission() {
        tapPermissionButton(getPermissionPositiveButtonUiObject())
        awaitPermissionDialogGone(hasTestTag(ScanTag.QR_FRAME))
    }

    fun denyPermission() {
        tapPermissionButton(getPermissionNegativeButtonUiObject())
        awaitPermissionDialogGone(hasText(getStringResource(R.string.scan_settings_button), ignoreCase = true))
    }

    // The system's "isn't responding" dialog for a launcher stalled on a cold emulator sits over the permission dialog
    // and takes its taps until it's closed.
    private fun tapPermissionButton(button: UiObject?) {
        assertNotNull(button)
        assertTrue(button!!.waitForExists(PERMISSION_DIALOG_TIMEOUT_MILLIS))
        val closeHungApp =
            UiDevice
                .getInstance(InstrumentationRegistry.getInstrumentation())
                .findObject(UiSelector().resourceId("android:id/aerr_close"))
        repeat(TAP_ATTEMPTS) {
            if (closeHungApp.exists()) closeHungApp.click()
            try {
                button.click()
            } catch (_: UiObjectNotFoundException) {
                return
            }
            if (button.waitUntilGone(TAP_WAIT_MILLIS)) return
        }
    }

    // While the system dialog closes the activity isn't resumed, and Compose counts no root until it is. The usual
    // waits require one, and fail on the spot; this one keeps looking until what the dialog was hiding shows.
    private fun awaitPermissionDialogGone(shown: SemanticsMatcher) {
        composeTestRule.waitUntil(PERMISSION_DIALOG_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodes(shown).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    @Composable
    fun DefaultContent() {
        ScanView(
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onScan = {},
            onImageScan = {},
            onOpenSettings = {
                onOpenSettingsCount.incrementAndGet()
            },
            onScanStateChange = {
                scanState.set(it)
            },
            validationResult = ScanValidationState.VALID
        )
    }

    fun setDefaultContent() {
        composeTestRule.setContent {
            ZcashTheme {
                DefaultContent()
            }
        }
    }
}
