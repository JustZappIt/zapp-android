package co.electriccoin.zcash.ui.screen.swap.upi.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepList
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.upi.OfframpAmountField
import co.electriccoin.zcash.ui.screen.swap.upi.OfframpFieldLabel

@Composable
internal fun BridgeToBaseView(state: BridgeToBaseState) {
    val c = ZappTheme.colors
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val pendingValue = stringResource(R.string.bridge_to_base_value_pending)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = HORIZONTAL_PADDING.dp, vertical = VERTICAL_PADDING.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = stringResource(R.string.bridge_to_base_title),
                    style = ZappTheme.typography.display.copy(color = c.text),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier =
                        Modifier
                            .size(INFO_TOUCH_SIZE.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = c.accent),
                                onClick = { showInfo = true },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
                        contentDescription = stringResource(R.string.upi_offramp_info_content_description),
                        tint = c.textMuted,
                        modifier = Modifier.size(INFO_ICON_SIZE.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(GAP_LG.dp))

            if (state.isInputVisible) {
                OfframpFieldLabel(stringResource(R.string.bridge_to_base_amount_label))
                Spacer(modifier = Modifier.height(GAP_SM.dp))
                ZappOfframpHeroAmountField(
                    symbol = stringResource(R.string.bridge_to_base_currency_symbol),
                    state = state.amountInput,
                    isError = state.isInsufficient,
                    secondaryText =
                        state.usdcEquivalentText?.let {
                            stringResource(R.string.bridge_to_base_hero_secondary, it.getValue())
                        },
                )
                Spacer(modifier = Modifier.height(GAP_LG.dp))
                ZappSettlementLedger(
                    rows =
                        buildList {
                            add(
                                ZappSettlementLedgerRow(
                                    stringResource(R.string.bridge_to_base_ledger_you_send),
                                    state.zecToSendText?.getValue() ?: pendingValue,
                                    state.isInsufficient,
                                ),
                            )
                            state.feeText?.let {
                                add(
                                    ZappSettlementLedgerRow(
                                        stringResource(R.string.bridge_to_base_ledger_fees),
                                        it.getValue(),
                                    ),
                                )
                            }
                            state.slippageText?.let {
                                add(
                                    ZappSettlementLedgerRow(
                                        stringResource(R.string.bridge_to_base_ledger_slippage),
                                        it.getValue(),
                                    ),
                                )
                            }
                            add(
                                ZappSettlementLedgerRow(
                                    stringResource(R.string.bridge_to_base_ledger_eta),
                                    state.etaValueText?.getValue() ?: pendingValue,
                                ),
                            )
                            add(
                                ZappSettlementLedgerRow(
                                    stringResource(R.string.bridge_to_base_ledger_base_now),
                                    state.baseBalanceText?.getValue() ?: pendingValue,
                                ),
                            )
                        },
                    notice = state.insufficientText?.getValue() ?: state.quoteStatusText?.getValue(),
                    noticeIsDanger = state.isInsufficient,
                )
                state.unavailableText?.let { warning ->
                    Spacer(modifier = Modifier.height(GAP_MD.dp))
                    BasicText(
                        text = warning.getValue(),
                        style = ZappTheme.typography.caption.copy(color = c.danger, fontWeight = FontWeight.Medium),
                    )
                }
            } else {
                state.bridgingAmountText?.let { amount ->
                    BasicText(
                        text = amount.getValue(),
                        style = ZappTheme.typography.caption.copy(color = c.text, fontWeight = FontWeight.Medium),
                    )
                    Spacer(modifier = Modifier.height(GAP_XS.dp))
                }
                state.etaText?.let { eta ->
                    BasicText(
                        text = eta.getValue(),
                        style = ZappTheme.typography.caption.copy(color = c.textMuted),
                    )
                    Spacer(modifier = Modifier.height(GAP_MD.dp))
                }
                ZappStepList(state.steps)
            }

            state.errorText?.let { err ->
                Spacer(modifier = Modifier.height(GAP_MD.dp))
                BasicText(
                    text = err.getValue(),
                    style = ZappTheme.typography.caption.copy(color = c.danger, fontWeight = FontWeight.Medium),
                )
            }
        }

        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction = {
                ZappButton(
                    text = state.primaryButton.text.getValue(),
                    enabled = state.primaryButton.isEnabled,
                    variant = ZappButtonVariant.Primary,
                    modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                    onClick = state.primaryButton.onClick,
                )
            },
        )
    }

    if (showInfo) {
        BridgeToBaseInfoSheet(onDismiss = { showInfo = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeToBaseInfoSheet(onDismiss: () -> Unit) {
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
            verticalArrangement = Arrangement.spacedBy(GAP_LG.dp),
        ) {
            BasicText(
                text = stringResource(R.string.upi_offramp_info_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            BasicText(
                text = stringResource(R.string.bridge_to_base_explainer),
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

private const val HORIZONTAL_PADDING = 18
private const val VERTICAL_PADDING = 16
private const val BOTTOM_BAR_GAP = 12
private const val SHEET_PADDING = 24
private const val INFO_TOUCH_SIZE = 32
private const val INFO_ICON_SIZE = 20
private const val GAP_XS = 4
private const val GAP_SM = 6
private const val GAP_MD = 10
private const val GAP_LG = 20

@PreviewScreens
@Composable
private fun PreviewInput() {
    ZcashTheme {
        BridgeToBaseView(
            state =
                BridgeToBaseState(
                    amountInput =
                        NumberTextFieldState(NumberTextFieldInnerState.fromAmount(java.math.BigDecimal("428"))) {},
                    baseBalanceText = stringRes("0.85 USDC"),
                    usdcEquivalentText = stringRes("≈ 5.03 USDC"),
                    zecToSendText = stringRes("You'll send ≈ 0.18 ZEC"),
                    isInsufficient = false,
                    insufficientText = null,
                    feeText = stringRes("~0.0004 ZEC"),
                    slippageText = stringRes("1%"),
                    quoteStatusText = null,
                    etaValueText = stringRes("~10 min"),
                    etaText = stringRes("Estimated time: ~10 min"),
                    unavailableText = null,
                    errorText = null,
                    bridgingAmountText = null,
                    steps = emptyList(),
                    isInputVisible = true,
                    primaryButton = ButtonState(stringRes("Add funds"), isEnabled = true),
                    onBack = {},
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewInsufficient() {
    ZcashTheme {
        BridgeToBaseView(
            state =
                BridgeToBaseState(
                    amountInput =
                        NumberTextFieldState(NumberTextFieldInnerState.fromAmount(java.math.BigDecimal("4280"))) {},
                    baseBalanceText = stringRes("0.85 USDC"),
                    usdcEquivalentText = stringRes("≈ 50.35 USDC"),
                    zecToSendText = stringRes("You'll send ≈ 1.80 ZEC"),
                    isInsufficient = true,
                    insufficientText = stringRes("Not enough ZEC. Your spendable balance is 0.30 ZEC."),
                    feeText = stringRes("~0.0004 ZEC"),
                    slippageText = stringRes("1%"),
                    quoteStatusText = null,
                    etaValueText = stringRes("~10 min"),
                    etaText = stringRes("Estimated time: ~10 min"),
                    unavailableText = null,
                    errorText = null,
                    bridgingAmountText = null,
                    steps = emptyList(),
                    isInputVisible = true,
                    primaryButton = ButtonState(stringRes("Add funds"), isEnabled = false),
                    onBack = {},
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewBridging() {
    ZcashTheme {
        BridgeToBaseView(
            state =
                BridgeToBaseState(
                    amountInput =
                        NumberTextFieldState(NumberTextFieldInnerState.fromAmount(java.math.BigDecimal("428"))) {},
                    baseBalanceText = stringRes("0.85 USDC"),
                    usdcEquivalentText = null,
                    zecToSendText = null,
                    isInsufficient = false,
                    insufficientText = null,
                    feeText = null,
                    slippageText = null,
                    quoteStatusText = null,
                    etaValueText = null,
                    etaText = stringRes("Estimated time: ~2 min"),
                    unavailableText = null,
                    errorText = null,
                    bridgingAmountText = stringRes("Adding ≈ ₹428 · 5.03 USDC"),
                    steps =
                        listOf(
                            ZappStep(stringRes("Bridging from ZEC via NEAR"), ZappStepStatus.InProgress),
                            ZappStep(stringRes("Funds arrived on Base"), ZappStepStatus.Pending),
                        ),
                    isInputVisible = false,
                    primaryButton = ButtonState(stringRes("Bridging…"), isEnabled = false),
                    onBack = {},
                ),
        )
    }
}
