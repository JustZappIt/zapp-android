package co.electriccoin.zcash.ui.screen.invest.buy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappFieldBalance
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_LG
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_MD
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_SM
import co.electriccoin.zcash.ui.screen.invest.common.InvestNotice
import co.electriccoin.zcash.ui.screen.invest.common.InvestScreenFrame
import co.electriccoin.zcash.ui.screen.invest.common.InvestTradeInProgressNotice
import java.math.BigDecimal

@Composable
internal fun InvestBuyView(state: InvestBuyState) {
    val c = ZappTheme.colors
    var showInfo by rememberSaveable { mutableStateOf(false) }
    InvestScreenFrame(
        title = state.title.getValue(),
        onBack = state.onBack,
        primaryButton = state.primaryButton,
        isPrimaryLoading = state.isPreparing,
        onInfo = { showInfo = true },
    ) {
        BasicText(
            text = stringResource(R.string.invest_buy_amount_label),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
        Spacer(Modifier.height(INVEST_GAP_SM.dp))
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
        Spacer(Modifier.height(INVEST_GAP_MD.dp))
        Presets(state.presets)
        Spacer(Modifier.height(INVEST_GAP_LG.dp))
        state.tradeInProgress?.let {
            InvestTradeInProgressNotice(it)
            Spacer(Modifier.height(INVEST_GAP_MD.dp))
        }
        state.ledger?.let { ledger -> Ledger(ledger, state) }
        state.noPrice?.let { NoPriceCard(it) }
    }
    if (showInfo) {
        InvestBuyInfoSheet(onDismiss = { showInfo = false })
    }
}

@Composable
private fun Presets(presets: List<InvestPresetState>) {
    Row(horizontalArrangement = Arrangement.spacedBy(INVEST_GAP_SM.dp), modifier = Modifier.fillMaxWidth()) {
        presets.forEach { preset ->
            ZappButton(
                text = preset.label.getValue(),
                variant = ZappButtonVariant.Secondary,
                enabled = preset.isEnabled,
                modifier = Modifier.weight(1f),
                onClick = preset.onClick,
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
                    stringResource(R.string.invest_buy_ledger_you_get),
                    ledger.youGet?.getValue() ?: pending,
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_buy_ledger_fees),
                    ledger.fees?.getValue() ?: pending,
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_buy_ledger_refund),
                    stringResource(R.string.invest_buy_ledger_refund_value),
                ),
                ZappSettlementLedgerRow(
                    stringResource(R.string.invest_buy_ledger_eta),
                    ledger.eta?.getValue() ?: pending,
                ),
            ),
        notice = state.notice?.getValue(),
        noticeIsDanger = state.isNoticeDanger,
    )
}

@Composable
private fun NoPriceCard(state: InvestNoPriceState) {
    InvestNotice(
        title = stringResource(R.string.invest_buy_no_price_title),
        body = listOfNotNull(state.body.getValue(), state.reopen?.getValue()).joinToString("\n\n"),
    ) {
        ZappButton(
            text = stringResource(R.string.invest_buy_try_again),
            variant = ZappButtonVariant.Ghost,
            loading = state.isRetrying,
            enabled = !state.isRetrying,
            modifier = Modifier.weight(1f),
            onClick = state.onTryAgain,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InvestBuyInfoSheet(onDismiss: () -> Unit) {
    val c = ZappTheme.colors
    ZashiScreenModalBottomSheet(onDismissRequest = onDismiss) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = SHEET_PADDING.dp,
                        end = SHEET_PADDING.dp,
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
            verticalArrangement = Arrangement.spacedBy(INVEST_GAP_LG.dp),
        ) {
            BasicText(
                text = stringResource(R.string.invest_buy_info_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            BasicText(
                text = stringResource(R.string.invest_buy_info_body),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
            ZappButton(
                text = stringResource(co.electriccoin.zcash.ui.design.R.string.general_ok),
                variant = ZappButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}

private const val SHEET_PADDING = 24

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
    isPreparing = false,
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
