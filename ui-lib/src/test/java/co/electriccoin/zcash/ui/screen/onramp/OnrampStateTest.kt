// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnrampStateTest {
    @Test
    fun `a live failure retries the current order`() {
        var retryCalls = 0
        val action =
            state(
                progress = failed(OnrampFailureCode.UPSTREAM_FAILED),
                onRetry = { retryCalls++ },
            ).primaryAction

        assertEquals(stringRes(R.string.onramp_retry), action.text)

        action.onClick()
        assertEquals(1, retryCalls)
    }

    @Test
    fun `a terminal failure starts over`() {
        val action = state(progress = failed(OnrampFailureCode.ORDER_EXPIRED)).primaryAction

        assertEquals(stringRes(R.string.onramp_start_over), action.text)
    }

    @Test
    fun `back settles only where the dock offers done or start over`() {
        // Settled: Back returns to amount entry. Live: Back leaves the screen, checkpoint intact.
        assertTrue(state(progress = completed(), mode = OnrampMode.COMPLETION).isSettled)
        assertTrue(state(progress = completed(), mode = OnrampMode.REFUNDED_TO_BASE).isSettled)
        assertTrue(state(progress = failed(OnrampFailureCode.ORDER_EXPIRED)).isSettled)
        assertTrue(state(progress = OnrampStatus.Cancelled("onramp-id", "order-id")).isSettled)
        assertFalse(state(progress = failed(OnrampFailureCode.UPSTREAM_FAILED)).isSettled)
        assertFalse(state(progress = OnrampStatus.AwaitingMerchant("onramp-id", "order-id")).isSettled)
        assertFalse(state(progress = completed(), mode = OnrampMode.DELIVERY_NEEDS_ATTENTION).isSettled)
    }

    private fun state(
        progress: OnrampStatus,
        onRetry: () -> Unit = {},
        mode: OnrampMode = OnrampMode.PROGRESS,
    ) = OnrampState(
        mode = mode,
        currency = CurrencyCode.Inr,
        paymentRail = stringRes("UPI"),
        amountInput = NumberTextFieldState(onValueChange = {}),
        progress = progress,
        onBack = {},
        onRetry = onRetry,
        onContinue = {},
        onDestinationSelected = {},
        onCopyAccountAddress = {},
        onSendBaseBalanceToZec = {},
        onConfirmSendBaseBalanceToZec = {},
        onDismissSendBaseBalanceToZec = {},
        onCopyPaymentAddress = {},
        onPaid = {},
        onConfirmPaid = {},
        onDismissPaidConfirm = {},
        onCancel = {},
        onRaiseLimit = {},
        onDeliveryAction = {},
        onDone = {},
    )

    private fun failed(code: OnrampFailureCode) =
        OnrampStatus.Failed(
            code = code,
            phase = OnrampPhase.AWAITING_SETTLEMENT,
            id = "onramp-id",
            orderId = "order-id",
        )

    private fun completed() =
        OnrampStatus.Completed(
            id = "onramp-id",
            orderId = "order-id",
            netUsdc = Usdc6.ofMicros(1_000_000L),
            fiatAmount = Usdc6.ofMicros(100_000_000L),
            paidTx = null,
            recipientAddress = Address.parse("0x448f857ea117138e85d062c6ce89e90a337874d6"),
        )
}
