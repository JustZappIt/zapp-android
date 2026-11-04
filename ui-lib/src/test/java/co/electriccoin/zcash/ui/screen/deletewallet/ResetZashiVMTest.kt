// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.deletewallet

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.usecase.EnsureNoUnfinishedConversionsUseCase
import co.electriccoin.zcash.ui.common.usecase.EnsureNoUnsharedGiftFundsUseCase
import co.electriccoin.zcash.ui.common.usecase.UnfinishedConversionsException
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.refunds.PrivateUsdRefundsArgs
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals

/** Losing a conversion takes the user's word, and then the same last confirmation as any other reset. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetZashiVMTest {
    private val resetZashi =
        mockk<ResetZashiUseCase> {
            every { pinPrompt } returns MutableStateFlow<PinVerifyState?>(null)
            coEvery { this@mockk(any(), any(), any(), any()) } returns ResetZashiResult.WIPED
        }
    private val gifts = mockk<EnsureNoUnsharedGiftFundsUseCase> { coEvery { this@mockk() } just Runs }
    private val conversions = mockk<EnsureNoUnfinishedConversionsUseCase> { coEvery { this@mockk() } just Runs }
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private lateinit var vm: ResetZashiVM

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `delete anyway asks once more, and only then wipes with the loss accepted`() =
        runTest {
            coEvery { conversions() } throws UnfinishedConversionsException(count = 1)
            start()

            content().buttonState.onClick()
            assertEquals(stringRes(R.string.delete_wallet_conversions_title), dialog().title)
            checkNotNull(dialog().secondaryAction).onClick()

            assertEquals(stringRes(R.string.delete_wallet_confirmation_title), dialog().title)
            coVerify(exactly = 0) { resetZashi(any(), any(), any(), any()) }

            dialog().primaryAction.onClick()
            coVerify(exactly = 1) {
                resetZashi(keepFiles = true, allowGiftDataLoss = false, allowConversionLoss = true, any())
            }
        }

    @Test
    fun `a plain reset accepts no loss`() =
        runTest {
            start()

            content().buttonState.onClick()
            dialog().primaryAction.onClick()

            coVerify(exactly = 1) {
                resetZashi(keepFiles = true, allowGiftDataLoss = false, allowConversionLoss = false, any())
            }
        }

    @Test
    fun `a refund to recover opens the refunds`() =
        runTest {
            coEvery { conversions() } throws UnfinishedConversionsException(count = 0, hasRefund = true)
            start()

            content().buttonState.onClick()
            assertEquals(stringRes(R.string.delete_wallet_refund_title), dialog().title)
            dialog().primaryAction.onClick()

            verify { navigation.forward(PrivateUsdRefundsArgs) }
        }

    @Test
    fun `records that can't be read offer another look, which may find nothing in the way`() =
        runTest {
            coEvery { conversions() } throws UnfinishedConversionsException(count = null)
            start()

            content().buttonState.onClick()
            assertEquals(stringRes(R.string.delete_wallet_conversions_unreadable_title), dialog().title)
            coEvery { conversions() } just Runs
            dialog().primaryAction.onClick()

            assertEquals(stringRes(R.string.delete_wallet_confirmation_title), dialog().title)
        }

    @Test
    fun `a seed that wouldn't clear says so, and can be tried again`() =
        runTest {
            coEvery { resetZashi(any(), any(), any(), any()) } returns ResetZashiResult.SEED_NOT_CLEARED
            start()

            content().buttonState.onClick()
            dialog().primaryAction.onClick()

            assertEquals(stringRes(R.string.delete_wallet_failed), dialog().message)
            dialog().primaryAction.onClick()
            coVerify(exactly = 2) { resetZashi(any(), any(), any(), any()) }
        }

    private fun content(): ResetZashiState = checkNotNull(vm.state.value.content)

    private fun dialog(): ZashiConfirmationState = checkNotNull(content().confirmationDialog)

    private fun TestScope.start() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        vm = ResetZashiVM(navigation, resetZashi, mockk(relaxed = true), gifts, conversions)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }
}
