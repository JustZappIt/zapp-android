package co.electriccoin.zcash.ui.screen.transactiondetail.info

import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.design.component.SwapQuoteHeaderState
import co.electriccoin.zcash.ui.design.component.ZashiMessageState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.StringResourceColor
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.util.StyledStringStyle
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle

sealed interface TransactionDetailInfoState

/**
 * Fee row message: the ZEC [fee], with a muted ` · <fiat>` appended when [feeFiat] is non-null
 * (null when no rate is available); otherwise just the styled fee.
 */
fun transactionFeeMessage(
    fee: StringResource,
    feeFiat: StringResource?,
): StyledStringResource =
    if (feeFiat == null) {
        fee.withStyle()
    } else {
        fee.withStyle() +
            stringRes(" · ").withStyle(StyledStringStyle(color = StringResourceColor.QUARTERNARY)) +
            feeFiat.withStyle(StyledStringStyle(color = StringResourceColor.QUARTERNARY))
    }

data class SendShieldedState(
    val contact: StringResource?,
    val address: StyledStringResource,
    val transactionId: StringResource,
    val onTransactionIdClick: () -> Unit,
    val onTransactionAddressClick: () -> Unit,
    val fee: StringResource,
    val feeFiat: StringResource?,
    val completedTimestamp: StringResource,
    val memo: TransactionDetailMemosState,
    val note: StringResource?,
    val isPending: Boolean
) : TransactionDetailInfoState

data class SendSwapState(
    val status: SwapStatus?,
    val message: ZashiMessageState?,
    val quoteHeader: SwapQuoteHeaderState,
    val depositAddress: StyledStringResource,
    val totalFees: StringResource?,
    val exchangeRate: StringResource?,
    val totalFeesUsd: StringResource?,
    val recipientAddress: StyledStringResource?,
    val transactionId: StringResource,
    val refundedAmount: StringResource?,
    val onTransactionIdClick: () -> Unit,
    val onDepositAddressClick: () -> Unit,
    val onRecipientAddressClick: (() -> Unit)?,
    val maxSlippage: StringResource?,
    val note: StringResource?,
    val isSlippageRealized: Boolean,
    val isPending: Boolean,
    val completedTimestamp: StringResource,
) : TransactionDetailInfoState

data class SendTransparentState(
    val contact: StringResource?,
    val address: StringResource,
    val addressAbbreviated: StyledStringResource,
    val transactionId: StringResource,
    val onTransactionIdClick: () -> Unit,
    val onTransactionAddressClick: () -> Unit,
    val fee: StringResource,
    val feeFiat: StringResource?,
    val completedTimestamp: StringResource,
    val note: StringResource?,
    val isPending: Boolean
) : TransactionDetailInfoState

data class ReceiveShieldedState(
    val memo: TransactionDetailMemosState,
    val transactionId: StringResource,
    val onTransactionIdClick: () -> Unit,
    val completedTimestamp: StringResource,
    val note: StringResource?,
    val isPending: Boolean
) : TransactionDetailInfoState

data class ReceiveTransparentState(
    val transactionId: StringResource,
    val onTransactionIdClick: () -> Unit,
    val completedTimestamp: StringResource,
    val note: StringResource?,
    val isPending: Boolean
) : TransactionDetailInfoState

data class ShieldingState(
    val transactionId: StringResource,
    val onTransactionIdClick: () -> Unit,
    val completedTimestamp: StringResource,
    val fee: StringResource,
    val feeFiat: StringResource?,
    val note: StringResource?,
    val isPending: Boolean
) : TransactionDetailInfoState

data class TransactionDetailMemosState(
    val memos: List<TransactionDetailMemoState>?,
)

data class TransactionDetailMemoState(
    val content: StringResource,
    val onClick: () -> Unit
)
