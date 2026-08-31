// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.p2p.CurrencyCode
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun state(
        progress: OnrampStatus,
        onRetry: () -> Unit = {},
    ) = OnrampState(
        mode = OnrampMode.PROGRESS,
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
}
