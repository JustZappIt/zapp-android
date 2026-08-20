// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.viewingkeyexport

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.fixture.AccountFixture
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.common.security.SecretAuthPolicy
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetViewingKeyExportDataUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareViewingKeyUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyExportAccount
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyExportData
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyType
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ViewingKeyExportVMTest {
    @Test
    fun authenticationSuccessRevealsAndBackgroundHidesKey() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val foreground = MutableStateFlow(true)
                val fixture = Fixture(foreground = foreground, authenticated = true)
                val vm = fixture.createVm()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                advanceUntilIdle()

                vm.state.value.onAcknowledgementChanged(true)
                vm.state.value.onReveal()
                advanceUntilIdle()
                assertSame(fixture.available, vm.state.value.revealedKey)
                coVerify(exactly = 1) {
                    fixture.secretAuthGate.authenticate(
                        any(),
                        SecretAuthPolicy.REQUIRE_AUTHENTICATION,
                    )
                }

                foreground.value = false
                advanceUntilIdle()
                assertNull(vm.state.value.revealedKey)
                vm.viewModelScope.cancel()
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun authenticationFailureKeepsKeyHidden() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = Fixture(authenticated = false)
                val vm = fixture.createVm()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                advanceUntilIdle()

                vm.state.value.onAcknowledgementChanged(true)
                vm.state.value.onReveal()
                advanceUntilIdle()

                assertNull(vm.state.value.revealedKey)
                assertEquals(ViewingKeyExportError.AUTHENTICATION_FAILED, vm.state.value.error)
                vm.viewModelScope.cancel()
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun copyUsesSensitiveClipboardFlag() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = Fixture(authenticated = true)
                val vm = fixture.createVm()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                advanceUntilIdle()
                vm.state.value.onAcknowledgementChanged(true)
                vm.state.value.onReveal()
                advanceUntilIdle()

                vm.state.value.onCopy()

                verify(exactly = 1) { fixture.copyToClipboard(RAW_KEY, isSensitive = true) }
                vm.viewModelScope.cancel()
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun shareUsesRawViewingKey() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = Fixture(authenticated = true)
                val vm = fixture.createVm()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                advanceUntilIdle()
                vm.state.value.onAcknowledgementChanged(true)
                vm.state.value.onReveal()
                advanceUntilIdle()

                vm.state.value.onShare("Share viewing key")

                verify(exactly = 1) { fixture.shareViewingKey(RAW_KEY, "Share viewing key") }
                vm.viewModelScope.cancel()
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun selectionCannotChangeWhileAuthenticatedLookupIsPending() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val lookupResult = CompletableDeferred<ViewingKeyExportData?>()
                val fixture = Fixture(authenticated = true, lookupResult = lookupResult)
                val vm = fixture.createVm()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                advanceUntilIdle()

                vm.state.value.onAcknowledgementChanged(true)
                vm.state.value.onReveal()
                runCurrent()
                assertTrue(vm.state.value.isAuthenticating)

                vm.state.value.onKeyTypeSelected(ViewingKeyType.UIVK)
                assertEquals(ViewingKeyType.UFVK, vm.state.value.selectedKeyType)

                lookupResult.complete(fixture.available)
                advanceUntilIdle()
                assertSame(fixture.available, vm.state.value.revealedKey)
                vm.viewModelScope.cancel()
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun backgroundCancelsPendingRevealAcrossNextForegroundSession() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val foreground = MutableStateFlow(true)
                val lookupResult = CompletableDeferred<ViewingKeyExportData?>()
                val fixture =
                    Fixture(
                        foreground = foreground,
                        authenticated = true,
                        lookupResult = lookupResult,
                    )
                val vm = fixture.createVm()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                advanceUntilIdle()

                vm.state.value.onAcknowledgementChanged(true)
                vm.state.value.onReveal()
                runCurrent()
                assertTrue(vm.state.value.isAuthenticating)

                foreground.value = false
                runCurrent()
                assertFalse(vm.state.value.isAuthenticating)
                assertNull(vm.state.value.revealedKey)

                foreground.value = true
                lookupResult.complete(fixture.available)
                advanceUntilIdle()
                assertNull(vm.state.value.revealedKey)
                vm.viewModelScope.cancel()
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }

    private class Fixture(
        foreground: MutableStateFlow<Boolean> = MutableStateFlow(true),
        authenticated: Boolean,
        private val lookupResult: CompletableDeferred<ViewingKeyExportData?>? = null,
    ) {
        private val sdkAccount = AccountFixture.new(uivk = UIVK)
        private val account =
            ViewingKeyExportAccount(
                accountId = sdkAccount.accountUuid,
                label = stringRes(R.string.accounts_zashi),
                accountIndex = 0,
                isSelected = true,
                availableKeyTypes = setOf(ViewingKeyType.UFVK, ViewingKeyType.UIVK),
            )
        val available = ViewingKeyExportData(keyType = ViewingKeyType.UFVK, encodedKey = RAW_KEY)
        val copyToClipboard = mockk<CopyToClipboardUseCase>(relaxed = true)
        val shareViewingKey =
            mockk<ShareViewingKeyUseCase>().also { shareViewingKey ->
                every {
                    shareViewingKey.invoke(any<String>(), any<String>())
                } returns true
            }
        private val getViewingKeyExportData = mockk<GetViewingKeyExportDataUseCase>()
        val secretAuthGate =
            mockk<SecretAuthGate> {
                every { pinPrompt } returns MutableStateFlow<PinVerifyState?>(null)
                coEvery { authenticate(any(), any()) } returns authenticated
            }
        private val applicationStateProvider = FakeApplicationStateProvider(foreground)

        fun createVm(): ViewingKeyExportVM {
            coEvery { getViewingKeyExportData.getAccounts() } returns listOf(account)
            coEvery {
                getViewingKeyExportData.invoke(account.accountId, ViewingKeyType.UFVK)
            } coAnswers { lookupResult?.await() ?: available }
            return ViewingKeyExportVM(
                getViewingKeyExportData = getViewingKeyExportData,
                shareViewingKey = shareViewingKey,
                copyToClipboard = copyToClipboard,
                secretAuthGate = secretAuthGate,
                applicationStateProvider = applicationStateProvider,
                navigationRouter = FakeNavigationRouter(),
            )
        }
    }

    private class FakeApplicationStateProvider(
        override val isInForeground: Flow<Boolean>,
    ) : ApplicationStateProvider {
        override fun onThirdPartyUiShown() = Unit

        override fun onApplicationLifecycleChanged(event: Lifecycle.Event) = Unit

        override fun observeOnForeground(): Flow<Unit> = emptyFlow()
    }

    private class FakeNavigationRouter : NavigationRouter {
        override fun forward(vararg routes: Any) = Unit

        override fun replace(vararg routes: Any) = Unit

        override fun replaceAll(vararg routes: Any) = Unit

        override fun back() = Unit

        override fun backTo(route: KClass<*>) = Unit

        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }

    private companion object {
        const val RAW_KEY = "uview1vm-test-secret"
        const val UIVK = "uivk1vm-test-secret"
    }
}
