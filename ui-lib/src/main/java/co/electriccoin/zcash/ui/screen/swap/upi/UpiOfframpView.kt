package co.electriccoin.zcash.ui.screen.swap.upi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import xyz.justzappit.offramp.p2p.CurrencyCode
import java.math.BigDecimal

@Composable
internal fun UpiOfframpBody(
    onBack: () -> Unit = {},
    currency: CurrencyCode = CurrencyCode.Inr,
    prescannedMerchantQr: PrescannedMerchantQr = PrescannedMerchantQr.EMPTY,
) {
    val vm = koinViewModel<UpiOfframpVM> { parametersOf(currency, prescannedMerchantQr) }
    val state by vm.state.collectAsStateWithLifecycle()
    val payConfirmation by vm.payConfirmation.collectAsStateWithLifecycle()
    UpiOfframpView(state = state, onBack = onBack)
    ZappConfirmationBottomSheet(state = payConfirmation)
}

@Composable
internal fun UpiOfframpView(
    state: UpiOfframpState,
    onBack: () -> Unit = {},
) {
    val c = ZappTheme.colors
    val corridor = state.currency.toOfframpCorridorUi()

    Column(modifier = Modifier.fillMaxSize().background(c.bg)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = BODY_HORIZONTAL_PADDING.dp, vertical = BODY_VERTICAL_PADDING.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OfframpFieldLabel(stringResource(R.string.upi_offramp_amount_label))
                    BasicText(
                        text = state.currency.code,
                        style = ZappTheme.typography.caption.copy(color = c.textMuted),
                    )
                }
                Spacer(modifier = Modifier.height(GAP_SM.dp))
                ZappOfframpHeroAmountField(
                    symbol = state.currency.symbol,
                    state = state.inrInput,
                    flag = painterResource(corridor.flag),
                    secondaryText =
                        state.usdcEquivalent?.let {
                            stringResource(R.string.upi_offramp_hero_secondary, it.getValue())
                        },
                )

                Spacer(modifier = Modifier.height(GAP_LG.dp))
                ZappSettlementLedger(
                    rows =
                        listOf(
                            ZappSettlementLedgerRow(
                                stringResource(R.string.upi_offramp_ledger_amount_sent),
                                state.fiatAmountText?.getValue().orEmpty(),
                            ),
                            ZappSettlementLedgerRow(
                                stringResource(R.string.upi_offramp_ledger_rate),
                                state.rateText.getValue(),
                            ),
                            ZappSettlementLedgerRow(
                                stringResource(R.string.upi_offramp_ledger_from),
                                state.baseBalanceText?.getValue() ?: stringResource(R.string.upi_offramp_base),
                            ),
                        ),
                    notice = state.fundingPlanText?.getValue().takeIf { state.isTopUpNeeded },
                )

                state.errorText?.let { err ->
                    Spacer(modifier = Modifier.height(GAP_MD.dp))
                    BasicText(
                        text = err.getValue(),
                        style =
                            ZappTheme.typography.caption.copy(
                                color = c.danger,
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(GAP_LG.dp))
                ZappButton(
                    text = stringResource(R.string.upi_offramp_add_funds_button),
                    variant = ZappButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = state.onAddFunds,
                )

                state.onDiscardInFlight?.let { onDiscard ->
                    Spacer(modifier = Modifier.height(GAP_SM.dp))
                    BasicText(
                        text = stringResource(R.string.upi_offramp_discard_in_flight),
                        style =
                            ZappTheme.typography.caption.copy(
                                color = c.danger,
                                textDecoration = TextDecoration.Underline,
                            ),
                        modifier = Modifier.clickable(onClick = onDiscard),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.height(GAP_LG.dp))
                RecentTransactionsRow(onHistoryClick = state.onHistoryClick)
            }
        }

        ZappBottomActionBar(
            onBack = onBack,
            primaryAction = {
                ZappButton(
                    text = state.sendButton.text.getValue(),
                    enabled = state.sendButton.isEnabled,
                    variant = ZappButtonVariant.Primary,
                    modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                    onClick = state.sendButton.onClick,
                )
            },
        )
    }
}

@Composable
private fun RecentTransactionsRow(onHistoryClick: () -> Unit) {
    val c = ZappTheme.colors
    val recentTransactions = stringResource(R.string.upi_offramp_recent_transactions)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = c.accent),
                        onClick = onHistoryClick,
                    ).semantics {
                        contentDescription = recentTransactions
                        role = Role.Button
                    }.padding(horizontal = 8.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            BasicText(
                text = recentTransactions,
                style = ZappTheme.typography.caption.copy(color = c.accent, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

private const val BODY_HORIZONTAL_PADDING = 18
private const val BODY_VERTICAL_PADDING = 12
private const val BOTTOM_BAR_GAP = 12
private const val GAP_SM = 6
private const val GAP_MD = 10
private const val GAP_LG = 16

@PreviewScreens
@Composable
private fun PreviewEmpty() {
    ZcashTheme {
        UpiOfframpView(
            state =
                UpiOfframpState(
                    inrInput = NumberTextFieldState(NumberTextFieldInnerState(), onValueChange = {}),
                    usdcEquivalent = null,
                    rateText = stringRes("1 USDC ≈ ₹85"),
                    errorText = null,
                    sendButton = ButtonState(stringRes("Pay")),
                    onHistoryClick = {},
                    onAddFunds = {},
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewFilled() {
    ZcashTheme {
        UpiOfframpView(
            state =
                UpiOfframpState(
                    inrInput =
                        NumberTextFieldState(
                            NumberTextFieldInnerState.fromAmount(BigDecimal("500.00")),
                            onValueChange = {},
                        ),
                    usdcEquivalent = stringRes("≈ 5.88 USDC"),
                    rateText = stringRes("1 USDC ≈ ₹85"),
                    errorText = null,
                    sendButton = ButtonState(stringRes("Pay"), isEnabled = true),
                    onHistoryClick = {},
                    onAddFunds = {},
                    baseBalanceText = stringRes("0.85 USDC"),
                    fundingPlanText = stringRes("You'll add about 5.04 USDC to Base first, then pay."),
                ),
        )
    }
}
