// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.viewingkeyexport

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.filters.MediumTest
import cash.z.ecc.android.sdk.fixture.AccountFixture
import co.electriccoin.zcash.test.UiTestPrerequisites
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.LocalScreenSecurity
import co.electriccoin.zcash.ui.common.compose.ScreenSecurity
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyExportAccount
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyExportData
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyType
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.test.getStringResource
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewingKeyExportViewTest : UiTestPrerequisites() {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @MediumTest
    fun secretIsAbsentAndConsentIsRequiredBeforeAuthentication() {
        setContent(state())

        composeTestRule.onNodeWithTag(ViewingKeyExportTag.REVEALED_KEY).assertDoesNotExist()
        composeTestRule.onNodeWithText(FAKE_KEY).assertDoesNotExist()
        composeTestRule
            .onNodeWithText(getStringResource(R.string.viewing_key_export_reveal))
            .assertIsNotEnabled()
    }

    @Test
    @MediumTest
    fun consentEnablesAuthentication() {
        setContent(state(isAcknowledged = true))

        composeTestRule
            .onNodeWithText(getStringResource(R.string.viewing_key_export_reveal))
            .assertIsEnabled()
    }

    @Test
    @MediumTest
    fun fullAndIncomingWarningsAreDifferent() {
        setContent(state())

        composeTestRule
            .onNodeWithText(getStringResource(R.string.viewing_key_export_ufvk_warning))
            .assertExists()
        composeTestRule
            .onNodeWithText(getStringResource(R.string.viewing_key_export_uivk_warning))
            .assertExists()
    }

    @Test
    @MediumTest
    fun backActionRemainsInBottomLeft() {
        setContent(state())

        val rootBounds = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val backBounds =
            composeTestRule
                .onNodeWithContentDescription(getStringResource(R.string.general_back_content_description))
                .getUnclippedBoundsInRoot()

        assertTrue(backBounds.left + backBounds.right < rootBounds.left + rootBounds.right)
        assertTrue(backBounds.top + backBounds.bottom > rootBounds.top + rootBounds.bottom)
    }

    @Test
    @MediumTest
    fun screenIsSecuredBeforeKeyIsRevealed() {
        val screenSecurity = ScreenSecurity()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalScreenSecurity provides screenSecurity) {
                ProvideZappTheme {
                    ViewingKeyExportView(state())
                }
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(1, screenSecurity.referenceCount.value)
        composeTestRule.onNodeWithTag(ViewingKeyExportTag.REVEALED_KEY).assertDoesNotExist()
    }

    private fun setContent(state: ViewingKeyExportState) {
        composeTestRule.setContent {
            ProvideZappTheme { ViewingKeyExportView(state) }
        }
        composeTestRule.waitForIdle()
    }

    private fun state(
        isAcknowledged: Boolean = false,
        revealedKey: ViewingKeyExportData? = null,
    ): ViewingKeyExportState =
        ViewingKeyExportState(
            accounts = listOf(account),
            selectedAccountId = account.accountId,
            selectedKeyType = ViewingKeyType.UFVK,
            isAcknowledged = isAcknowledged,
            isLoading = false,
            isAuthenticating = false,
            isCopied = false,
            revealedKey = revealedKey,
            error = null,
            pinVerify = null,
            onAccountSelected = {},
            onKeyTypeSelected = {},
            onAcknowledgementChanged = {},
            onReveal = {},
            onCopy = {},
            onShare = {},
            onHide = {},
            onBack = {},
        )

    private companion object {
        const val FAKE_KEY = "uview1-ui-test-only-fake-key"
        val accountFixture = AccountFixture.new()
        val account =
            ViewingKeyExportAccount(
                accountId = accountFixture.accountUuid,
                label = stringRes("Zapp"),
                accountIndex = 0,
                isSelected = true,
                availableKeyTypes = setOf(ViewingKeyType.UFVK, ViewingKeyType.UIVK),
            )
    }
}
