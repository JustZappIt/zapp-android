package co.electriccoin.zcash.ui.screen.onramp

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.onramp.FundsLocation
import xyz.justzappit.offramp.onramp.OnrampDestination
import xyz.justzappit.offramp.onramp.OnrampPaymentInstruction
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus
import xyz.justzappit.offramp.p2p.CurrencyCode

internal data class OnrampState(
    val mode: OnrampMode = OnrampMode.LOADING,
    val destination: OnrampDestination = OnrampDestination.BASE,
    val isZecDestinationEnabled: Boolean = true,
    val accountAddress: String? = null,
    val addressExplorerUrl: String? = null,
    val baseBalance: String? = null,
    val isBaseRefundSupported: Boolean = false,
    val canSendBaseBalanceToZec: Boolean = false,
    val isRequestingQuote: Boolean = false,
    val isSendingBaseBalanceToZec: Boolean = false,
    val isSendBaseBalanceConfirmVisible: Boolean = false,
    val sendBaseBalanceSuccess: StringResource? = null,
    val sendBaseBalanceError: StringResource? = null,
    val currency: CurrencyCode,
    val paymentRail: StringResource,
    val amountInput: NumberTextFieldState,
    val minFiat: String? = null,
    val maxFiat: String? = null,
    val dailyLimit: String? = null,
    val quotedFiat: String? = null,
    val quotedNetUsdc: String? = null,
    val quotedFee: String? = null,
    val quotedRate: String? = null,
    val isRequestingZecEstimate: Boolean = false,
    val estimatedZec: String? = null,
    val estimatedZecValue: String? = null,
    val estimatedConversionCost: String? = null,
    val quoteSecondsRemaining: Long? = null,
    val orderId: String? = null,
    val receivedUsdc: String? = null,
    val receivedZec: String? = null,
    val fiatPaid: String? = null,
    val transactionExplorerUrl: String? = null,
    val paymentInstruction: OnrampPaymentInstruction? = null,
    val paymentAmount: String? = null,
    val paymentSecondsRemaining: Long? = null,
    val isPaymentAmountUntrusted: Boolean = false,
    val progress: OnrampStatus? = null,
    val delivery: OnrampZecDeliveryStatus? = null,
    val error: StringResource? = null,
    val canContinue: Boolean = false,
    val isPaidConfirmVisible: Boolean = false,
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onContinue: () -> Unit,
    val onDestinationSelected: (OnrampDestination) -> Unit,
    val onCopyAccountAddress: () -> Unit,
    val onSendBaseBalanceToZec: () -> Unit,
    val onConfirmSendBaseBalanceToZec: () -> Unit,
    val onDismissSendBaseBalanceToZec: () -> Unit,
    val onCopyPaymentAddress: () -> Unit,
    val onPaid: () -> Unit,
    val onConfirmPaid: () -> Unit,
    val onDismissPaidConfirm: () -> Unit,
    val onCancel: () -> Unit,
    /** The daily limit is set by reputation, so the explanation is the screen that raises it. */
    val onRaiseLimit: () -> Unit,
    val onDeliveryAction: () -> Unit,
    val onDone: () -> Unit,
) {
    /**
     * The service's payment window has closed. Money sent after this is not settled against the
     * order, so the pay actions must go away rather than merely look stale.
     */
    val isPaymentWindowClosed: Boolean
        get() = paymentSecondsRemaining != null && paymentSecondsRemaining <= 0L

    /** Neither the window nor the amount may be in doubt before we hand the user to a payment app. */
    val isPayable: Boolean
        get() = !isPaymentWindowClosed && !isPaymentAmountUntrusted

    /** The order has settled against the user: nothing is left to cancel, only a way forward. */
    val isSettledAgainstUser: Boolean
        get() = progress is OnrampStatus.Failed || progress is OnrampStatus.Cancelled

    val isDeliveryFailed: Boolean
        get() = delivery is OnrampZecDeliveryStatus.Failed

    val canRetryDelivery: Boolean
        get() =
            (delivery as? OnrampZecDeliveryStatus.Failed)?.let {
                it.retryable && it.fundsLocation == FundsLocation.BASE_ACCOUNT
            } == true

    /** The single dock CTA for the mode on screen, in the shape every other Zapp dock uses. */
    val primaryAction: ButtonState
        get() =
            when (mode) {
                OnrampMode.LOADING -> {
                    ButtonState(stringRes(R.string.onramp_retry), isEnabled = false, onClick = onRetry)
                }

                OnrampMode.UNAVAILABLE -> {
                    ButtonState(stringRes(R.string.onramp_retry), onClick = onRetry)
                }

                OnrampMode.AMOUNT -> {
                    ButtonState(
                        stringRes(R.string.onramp_get_quote),
                        isEnabled = canContinue && !isSendingBaseBalanceToZec,
                        onClick = onContinue,
                    )
                }

                OnrampMode.CONFIRMATION -> {
                    ButtonState(
                        stringRes(R.string.onramp_place_order),
                        isEnabled = canContinue && !isSendingBaseBalanceToZec,
                        onClick = onContinue,
                    )
                }

                // A settled order leaves nothing to cancel, so the dock has to offer a way forward
                // rather than a disabled button and the back arrow.
                OnrampMode.PROGRESS -> {
                    if (isSettledAgainstUser) {
                        ButtonState(stringRes(R.string.onramp_start_over), onClick = onRetry)
                    } else {
                        ButtonState(
                            text = stringRes(R.string.onramp_cancel_order),
                            isEnabled = progress is OnrampStatus.AwaitingMerchant,
                            onClick = onCancel,
                        )
                    }
                }

                OnrampMode.PAYMENT -> {
                    if (isPayable) {
                        ButtonState(stringRes(R.string.onramp_i_have_paid), onClick = onPaid)
                    } else {
                        ButtonState(stringRes(R.string.onramp_start_over), onClick = onRetry)
                    }
                }

                OnrampMode.COMPLETION -> {
                    ButtonState(stringRes(R.string.onramp_done), onClick = onDone)
                }

                OnrampMode.CONVERTING_TO_ZEC -> {
                    ButtonState(stringRes(R.string.onramp_converting_action), isEnabled = false, onClick = {})
                }

                OnrampMode.REFUNDED_TO_BASE -> {
                    ButtonState(stringRes(R.string.onramp_done), onClick = onDone)
                }

                OnrampMode.DELIVERY_NEEDS_ATTENTION -> {
                    val failure = delivery as? OnrampZecDeliveryStatus.Failed
                    when {
                        canRetryDelivery -> {
                            ButtonState(stringRes(R.string.onramp_try_conversion_again), onClick = onDeliveryAction)
                        }

                        failure?.fundsLocation == FundsLocation.BASE_ACCOUNT ||
                            failure?.fundsLocation == FundsLocation.RECIPIENT_MISMATCH -> {
                            ButtonState(stringRes(R.string.onramp_done), onClick = onDone)
                        }

                        else -> {
                            ButtonState(stringRes(R.string.onramp_check_conversion_status), onClick = onDeliveryAction)
                        }
                    }
                }
            }

    /**
     * The payload to render as a QR: any rail's, whether or not it is a URI. A PIX code and a
     * QRIS string are scannable but never launchable, and neither is a UPI request — see
     * PaymentContent for why nothing here is handed to a payment app.
     */
    val qrPayload: String?
        get() =
            when (val instruction = paymentInstruction) {
                is OnrampPaymentInstruction.Upi -> instruction.intentUrl
                is OnrampPaymentInstruction.Qr -> instruction.payload
                else -> null
            }

    val currencyCode: String get() = currency.code

    val currencySymbol: String get() = currency.symbol

    val paymentAddress: String?
        get() =
            when (val instruction = paymentInstruction) {
                is OnrampPaymentInstruction.Upi -> {
                    instruction.address
                }

                is OnrampPaymentInstruction.Plain -> {
                    instruction.address
                }

                is OnrampPaymentInstruction.Qr -> {
                    instruction.payload
                }

                is OnrampPaymentInstruction.Fields -> {
                    instruction.fields.joinToString("\n") { "${it.label}: ${it.value}" }
                }

                null -> {
                    null
                }
            }
}

internal enum class OnrampMode {
    LOADING,
    UNAVAILABLE,
    AMOUNT,
    CONFIRMATION,
    PROGRESS,
    PAYMENT,
    CONVERTING_TO_ZEC,
    COMPLETION,
    REFUNDED_TO_BASE,
    DELIVERY_NEEDS_ATTENTION,
}
