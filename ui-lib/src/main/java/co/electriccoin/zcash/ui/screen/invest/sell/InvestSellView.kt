package co.electriccoin.zcash.ui.screen.invest.sell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappFieldBalance
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.component.zapp.ZappSegment
import co.electriccoin.zcash.ui.design.component.zapp.ZappSegmentedSelector
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.buy.InvestPresetState
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_LG
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_MD
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_SM
import co.electriccoin.zcash.ui.screen.invest.common.InvestNotice
import co.electriccoin.zcash.ui.screen.invest.common.InvestScreenFrame
import co.electriccoin.zcash.ui.screen.invest.common.InvestTradeInProgressNotice
import java.math.BigDecimal

@Composable
internal fun InvestSellView(state: InvestSellState) {
    if (state.isRefused) {
        RefusedContent(state)
        return
    }
    val c = ZappTheme.colors
    InvestScreenFrame(
        title = state.title.getValue(),
        onBack = state.onBack,
        primaryButton = state.primaryButton,
        isPrimaryLoading = state.isPreparing,
    ) {
        ZappSegmentedSelector(
            segments =
                listOf(
                    ZappSegment(stringResource(R.string.invest_sell_mode_money)),
                    ZappSegment(stringResource(R.string.invest_sell_mode_shares)),
                ),
            selectedIndex = state.mode.ordinal,
            onSelect = { state.onModeChange(SellAmountMode.entries[it]) },
        )
        Spacer(Modifier.height(INVEST_GAP_LG.dp))
        BasicText(
            text = stringResource(R.string.invest_sell_amount_label),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
        Spacer(Modifier.height(INVEST_GAP_SM.dp))
        ZappOfframpHeroAmountField(
            symbol = state.amountSymbol,
            state = state.amountInput,
            isError = state.isAmountError,
            balance =
                ZappFieldBalance(
                    label = stringResource(R.string.invest_sell_holding_label),
                    amount = state.holdingText.getValue(),
                ),
            secondaryText = null,
        )
        Spacer(Modifier.height(INVEST_GAP_MD.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(INVEST_GAP_SM.dp), modifier = Modifier.fillMaxWidth()) {
            state.presets.forEach { preset ->
                ZappButton(
                    text = preset.label.getValue(),
                    variant = ZappButtonVariant.Secondary,
                    enabled = preset.isEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = preset.onClick,
                )
            }
        }
        Spacer(Modifier.height(INVEST_GAP_LG.dp))
        state.tradeInProgress?.let {
            InvestTradeInProgressNotice(it)
            Spacer(Modifier.height(INVEST_GAP_MD.dp))
        }
        state.ledger?.let { Ledger(it, state) }
        state.sellAllSuggestion?.let { suggestion ->
            Spacer(Modifier.height(INVEST_GAP_MD.dp))
            InvestNotice(body = suggestion.text.getValue()) {
                ZappButton(
                    text = stringResource(R.string.invest_sell_preset_all),
                    variant = ZappButtonVariant.Ghost,
                    modifier = Modifier.weight(1f),
                    onClick = suggestion.onSellAll,
                )
            }
        }
        state.noPrice?.let { noPrice ->
            InvestNotice(
                title = stringResource(R.string.invest_buy_no_price_title),
                body = listOfNotNull(noPrice.body.getValue(), noPrice.reopen?.getValue()).joinToString("\n\n"),
            ) {
                ZappButton(
                    text = stringResource(R.string.invest_buy_try_again),
                    variant = ZappButtonVariant.Ghost,
                    loading = noPrice.isRetrying,
                    enabled = !noPrice.isRetrying,
                    modifier = Modifier.weight(1f),
                    onClick = noPrice.onTryAgain,
                )
            }
        }
    }
}

@Composable
private fun Ledger(
    ledger: InvestSellLedger,
    state: InvestSellState,
) {
    val pending = stringResource(R.string.invest_value_pending)
    ZappSettlementLedger(
        rows =
            listOf(
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_sell_ledger_you_sell),
                    ledger.youSell?.getValue() ?: pending,
                    state.isAmountError,
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_buy_ledger_you_get),
                    ledger.youGet?.getValue() ?: pending,
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_sell_ledger_fees),
                    ledger.fees?.getValue() ?: pending,
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_sell_ledger_paid_to),
                    stringResource(R.string.invest_sell_ledger_paid_to_value),
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_buy_ledger_eta),
                    ledger.eta?.getValue() ?: pending,
                ),
            ),
        notice = state.notice?.getValue() ?: ledger.feeNote?.getValue(),
        noticeIsDanger = state.isNoticeDanger,
    )
}

/** "Something did not match. Nothing was sent.": the generated intent wasn't the reviewed transfer. */
@Composable
private fun RefusedContent(state: InvestSellState) {
    InvestScreenFrame(
        title = stringResource(R.string.invest_sell_refused_title),
        onBack = state.onBack,
        primaryButton = ButtonState(stringRes(R.string.invest_sell_refused_back), onClick = state.onBack),
    ) {
        BasicText(
            text = stringResource(R.string.invest_sell_refused_body),
            style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
        )
    }
}

private fun previewState(isRefused: Boolean = false) =
    InvestSellState(
        title = stringRes("Sell NVIDIA"),
        mode = SellAmountMode.MONEY,
        amountSymbol = "$",
        amountInput = NumberTextFieldState(NumberTextFieldInnerState.fromAmount(BigDecimal("98.99"))) {},
        holdingText = stringRes("$98.99 · 0.4410 NVDA"),
        onModeChange = {},
        presets = listOf("50%", "Sell all").map { InvestPresetState(stringRes(it), isEnabled = true) {} },
        ledger =
            InvestSellLedger(
                youSell = stringRes("$98.99 · 0.4410 NVDA"),
                youGet = stringRes("≈ 0.0634 ZEC"),
                fees = stringRes("$1.12"),
                feeNote = stringRes("Includes a fixed 0.00064 ZEC withdrawal fee."),
                eta = stringRes("~3 min"),
            ),
        noPrice = null,
        notice = null,
        isNoticeDanger = false,
        isAmountError = false,
        sellAllSuggestion = null,
        primaryButton = ButtonState(stringRes("Review")),
        isPreparing = false,
        isRefused = isRefused,
        tradeInProgress = null,
        onBack = {},
    )

@PreviewScreens
@Composable
private fun PreviewSell() {
    ZcashTheme { InvestSellView(previewState()) }
}

@PreviewScreens
@Composable
private fun PreviewRefused() {
    ZcashTheme { InvestSellView(previewState(isRefused = true)) }
}
