// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:Suppress("TooManyFunctions")

package co.electriccoin.zcash.ui.screen.onramp

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.onramp.OnrampDestination
import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampPaymentInstruction
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigDecimal

@PreviewScreens
@Composable
private fun PreviewLoading() = PreviewOnramp(OnrampMode.LOADING)

@PreviewScreens
@Composable
private fun PreviewUnavailable() = PreviewOnramp(OnrampMode.UNAVAILABLE)

@PreviewScreens
@Composable
private fun PreviewAmount() = PreviewOnramp(OnrampMode.AMOUNT)

@PreviewScreens
@Composable
private fun PreviewAmountSendingToZec() =
    PreviewOnramp(
        mode = OnrampMode.AMOUNT,
        isSendingBaseBalanceToZec = true,
    )

@PreviewScreens
@Composable
private fun PreviewAmountSentToZec() =
    PreviewOnramp(
        mode = OnrampMode.AMOUNT,
        sendBaseBalanceSuccess = stringRes("Transfer complete. 4.5 USDC was converted to ZEC."),
    )

@PreviewScreens
@Composable
private fun PreviewAmountSendToZecFailed() =
    PreviewOnramp(
        mode = OnrampMode.AMOUNT,
        sendBaseBalanceError = stringRes("We couldn’t complete the transfer."),
    )

@PreviewScreens
@Composable
private fun PreviewConfirmation() = PreviewOnramp(OnrampMode.CONFIRMATION)

@PreviewScreens
@Composable
private fun PreviewProgressWaiting() =
    PreviewOnramp(
        OnrampMode.PROGRESS,
        progress = OnrampStatus.AwaitingMerchant(PREVIEW_ID, PREVIEW_ORDER_ID),
    )

@PreviewScreens
@Composable
private fun PreviewProgressFailed() =
    PreviewOnramp(
        OnrampMode.PROGRESS,
        progress =
            OnrampStatus.Failed(
                code = OnrampFailureCode.NO_MERCHANT,
                phase = OnrampPhase.AWAITING_MERCHANT,
                id = PREVIEW_ID,
                orderId = PREVIEW_ORDER_ID,
            ),
    )

@PreviewScreens
@Composable
private fun PreviewPayment() =
    PreviewOnramp(
        OnrampMode.PAYMENT,
        progress =
            OnrampStatus.AwaitingPayment(
                id = PREVIEW_ID,
                orderId = PREVIEW_ORDER_ID,
                instruction = PREVIEW_INSTRUCTION,
                fiatAmount = Usdc6.ofWhole(BigDecimal("100")),
                expiresAtMillis = null,
            ),
    )

@PreviewScreens
@Composable
private fun PreviewCompletion() =
    PreviewOnramp(
        OnrampMode.COMPLETION,
        progress =
            OnrampStatus.Completed(
                id = PREVIEW_ID,
                orderId = PREVIEW_ORDER_ID,
                netUsdc = Usdc6.ofWhole(BigDecimal("0.910153")),
                fiatAmount = Usdc6.ofWhole(BigDecimal("100")),
                paidTx = null,
                recipientAddress = Address.parse(PREVIEW_ADDRESS),
            ),
    )

@Composable
internal fun PreviewOnramp(
    mode: OnrampMode,
    progress: OnrampStatus? = null,
    delivery: OnrampZecDeliveryStatus? = null,
    destination: OnrampDestination = OnrampDestination.BASE,
    isSendingBaseBalanceToZec: Boolean = false,
    sendBaseBalanceSuccess: StringResource? = null,
    sendBaseBalanceError: StringResource? = null,
) = ZcashTheme {
    OnrampView(
        OnrampState(
            mode = mode,
            destination = destination,
            accountAddress = PREVIEW_ADDRESS,
            addressExplorerUrl = null,
            baseBalance = "4.5",
            isBaseRefundSupported = true,
            canSendBaseBalanceToZec = true,
            isSendingBaseBalanceToZec = isSendingBaseBalanceToZec,
            sendBaseBalanceSuccess = sendBaseBalanceSuccess,
            sendBaseBalanceError = sendBaseBalanceError,
            currency = CurrencyCode.Inr,
            paymentRail = stringRes("UPI"),
            amountInput =
                NumberTextFieldState(
                    innerState = NumberTextFieldInnerState(amount = BigDecimal("100")),
                    onValueChange = {},
                ),
            minFiat = "100",
            maxFiat = "500",
            dailyLimit = "1000",
            quotedFiat = "100",
            quotedNetUsdc = "0.910153",
            quotedFee = "0.05",
            quotedRate = "104.15",
            quoteSecondsRemaining = 72,
            orderId = PREVIEW_ORDER_ID,
            receivedUsdc = "0.910153",
            receivedZec = "0.019",
            fiatPaid = "100",
            transactionExplorerUrl = null,
            paymentInstruction = PREVIEW_INSTRUCTION.takeIf { mode == OnrampMode.PAYMENT },
            paymentAmount = "100",
            paymentSecondsRemaining = 540,
            progress = progress,
            delivery = delivery,
            error = null,
            canContinue = true,
            isPaidConfirmVisible = false,
            onBack = {},
            onRetry = {},
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
            onDeliveryAction = {},
            onDone = {},
        ),
    )
}

internal const val PREVIEW_ID = "00000000-0000-4000-8000-000000000000"
internal const val PREVIEW_ORDER_ID = "659007"
internal const val PREVIEW_ADDRESS = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"
internal val PREVIEW_INSTRUCTION =
    OnrampPaymentInstruction.Upi(
        address = "merchant@upi",
        intentUrl = "upi://pay?pa=merchant@upi&pn=Merchant&am=100.00&cu=INR&tr=659007",
        amount = "100.00",
    )
