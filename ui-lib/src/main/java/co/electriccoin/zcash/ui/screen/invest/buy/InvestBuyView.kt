package co.electriccoin.zcash.ui.screen.invest.buy

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappFieldBalance
import co.electriccoin.zcash.ui.design.component.zapp.ZappInfoSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestNoPriceCard
import co.electriccoin.zcash.ui.screen.invest.common.InvestNoPriceState
import co.electriccoin.zcash.ui.screen.invest.common.InvestPresetRow
import co.electriccoin.zcash.ui.screen.invest.common.InvestPresetState
import co.electriccoin.zcash.ui.screen.invest.common.InvestScreenFrame
import co.electriccoin.zcash.ui.screen.invest.common.InvestTradeInProgressNotice
import java.math.BigDecimal

@Composable
internal fun InvestBuyView(state: InvestBuyState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    var showInfo by rememberSaveable { mutableStateOf(false) }
    InvestScreenFrame(
        title = state.title.getValue(),
        onBack = state.onBack,
        primaryButton = state.primaryButton,
        onInfo = { showInfo = true },
    ) {
        BasicText(
            text = stringResource(R.string.invest_buy_amount_label),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
        Spacer(Modifier.height(spacing.md))
        ZappOfframpHeroAmountField(
            symbol = state.currencySymbol,
            state = state.amountInput,
            isError = state.isAmountError,
            balance =
                ZappFieldBalance(
                    label = stringResource(R.string.invest_buy_balance_label),
                    amount = state.balanceText.getValue(),
                ),
            secondaryText = null,
        )
        Spacer(Modifier.height(spacing.lg))
        InvestPresetRow(state.presets)
        Spacer(Modifier.height(spacing.xl2))
        state.sellOnly?.let {
            InvestTradeInProgressNotice(it)
            Spacer(Modifier.height(spacing.lg))
        }
        state.tradeInProgress?.let {
            InvestTradeInProgressNotice(it)
            Spacer(Modifier.height(spacing.lg))
        }
        state.ledger?.let { ledger -> Ledger(ledger, state) }
        state.noPrice?.let { InvestNoPriceCard(it) }
    }
    if (showInfo) {
        ZappInfoSheet(
            title = stringResource(R.string.invest_buy_info_title),
            steps = emptyList(),
            notes = emptyList(),
            onDismiss = { showInfo = false },
        ) {
            BasicText(
                text = stringResource(R.string.invest_buy_info_body),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
        }
    }
}

@Composable
private fun Ledger(
    ledger: InvestBuyLedger,
    state: InvestBuyState,
) {
    val pending = stringResource(R.string.invest_value_pending)
    ZappSettlementLedger(
        rows =
            listOf(
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_buy_ledger_you_send),
                    ledger.youSend?.getValue() ?: pending,
                    state.isAmountError,
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_ledger_you_get),
                    ledger.youGet?.getValue() ?: pending,
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_ledger_fees),
                    ledger.fees?.getValue() ?: pending,
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_buy_ledger_refund),
                    stringResource(R.string.invest_buy_ledger_refund_value),
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_ledger_eta),
                    ledger.eta?.getValue() ?: pending,
                ),
            ),
        notice = state.notice?.getValue(),
        noticeIsDanger = state.isNoticeDanger,
    )
}

private fun previewState(
    ledger: InvestBuyLedger?,
    noPrice: InvestNoPriceState?,
) = InvestBuyState(
    title = stringRes("Buy NVIDIA"),
    currencySymbol = "$",
    amountInput = NumberTextFieldState(NumberTextFieldInnerState.fromAmount(BigDecimal("100"))) {},
    balanceText = stringRes("0.7806 ZEC · $1,204.87"),
    presets =
        listOf("$40", "$100", "$250", "Max").map { InvestPresetState(stringRes(it), isEnabled = true) {} },
    ledger = ledger,
    noPrice = noPrice,
    notice = null,
    isNoticeDanger = false,
    isAmountError = false,
    primaryButton = ButtonState(stringRes("Review"), isEnabled = ledger != null),
    tradeInProgress = null,
    onBack = {},
)

@PreviewScreens
@Composable
private fun PreviewPriced() {
    ZcashTheme {
        InvestBuyView(
            previewState(
                ledger =
                    InvestBuyLedger(
                        youSend = stringRes("0.06478 ZEC"),
                        youGet = stringRes("≈ $99.01 · 0.4410 NVDA"),
                        fees = stringRes("$0.95 (0.95%)"),
                        eta = stringRes("~8 min"),
                    ),
                noPrice = null,
            ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewNoPrice() {
    ZcashTheme {
        InvestBuyView(
            previewState(
                ledger = null,
                noPrice =
                    InvestNoPriceState(
                        body = stringRes("No one is offering Apple at the moment."),
                        reopen = stringRes("Weekday trading reopens Mon 08:00"),
                        isRetrying = false,
                        onTryAgain = {},
                    ),
            ),
        )
    }
}
