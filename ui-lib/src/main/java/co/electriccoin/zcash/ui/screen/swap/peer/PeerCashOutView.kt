package co.electriccoin.zcash.ui.screen.swap.peer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappChipVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappFieldBalance
import co.electriccoin.zcash.ui.design.component.zapp.ZappInputField
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappStatusChip
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.peer.PeerPlatform

@Composable
internal fun PeerCashOutView(state: PeerCashOutState) {
    val c = ZappTheme.colors
    var showInfo by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .imePadding(),
    ) {
        ZappScreenHeader(
            title = state.title.getValue(),
            right = { InfoAction { showInfo = true } },
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = GUTTER.dp, vertical = VERTICAL_PADDING.dp),
        ) {
            SectionLabel(stringResource(R.string.peer_offramp_amount_label))
            Spacer(Modifier.height(GAP_SM.dp))
            ZappOfframpHeroAmountField(
                symbol = USDC_SYMBOL,
                state = state.amountInput,
                secondaryText = state.fiatEquivalent?.getValue(),
                balance =
                    ZappFieldBalance(
                        label = stringResource(R.string.offramp_field_balance_available),
                        amount = state.availableBalance.getValue(),
                    ),
                isError = state.amountError != null,
            )

            Spacer(Modifier.height(GAP_LG.dp))
            ZappSettlementLedger(
                rows =
                    state.ledger.map {
                        ZappSettlementLedgerRow(label = it.label.getValue(), value = it.value.getValue())
                    },
                notice = state.notice?.getValue(),
                noticeIsDanger = state.isNoticeDanger,
            )

            Spacer(Modifier.height(GAP_LG.dp))
            ZappButton(
                text = state.topUpButton.text.getValue(),
                variant = ZappButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
                onClick = state.topUpButton.onClick,
            )

            Spacer(Modifier.height(GAP_LG.dp))
            SectionLabel(
                stringResource(
                    if (state.platform.offersCurrencyChoice) {
                        R.string.peer_offramp_currencies_label
                    } else {
                        R.string.peer_offramp_currency_label
                    },
                ),
            )
            Spacer(Modifier.height(GAP_SM.dp))
            CurrencyChips(state.currencies)

            Spacer(Modifier.height(GAP_LG.dp))
            SectionLabel(stringResource(R.string.peer_offramp_handle_label))
            Spacer(Modifier.height(GAP_SM.dp))
            HandleField(state.handleField, state.handleHint.getValue())
            Spacer(Modifier.height(GAP_SM.dp))
            BasicText(
                text = (state.handleField.error ?: state.handleHint).getValue(),
                style =
                    ZappTheme.typography.caption.copy(
                        color = if (state.handleField.error != null) c.danger else c.textMuted,
                    ),
            )
            state.handleNormalized?.let {
                Spacer(Modifier.height(GAP_SM.dp))
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.caption.copy(color = c.text, fontWeight = FontWeight.Black),
                )
            }
            state.handleUnverified?.let {
                Spacer(Modifier.height(GAP_SM.dp))
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.caption.copy(color = c.text),
                )
            }

            if (state.activeOrders.isNotEmpty()) {
                Spacer(Modifier.height(GAP_LG.dp))
                SectionLabel(stringResource(R.string.peer_offramp_active_orders_label))
                Spacer(Modifier.height(GAP_SM.dp))
                state.activeOrders.forEach { order ->
                    PeerActiveOrderRow(order)
                    Spacer(Modifier.height(GAP_SM.dp))
                }
            }
        }
        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction = {
                ZappButton(
                    text = state.primaryButton.text.getValue(),
                    enabled = state.primaryButton.isEnabled,
                    modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                    onClick = state.primaryButton.onClick,
                )
            },
        )
    }
    if (showInfo) PeerCashOutInfoSheet(state.platform) { showInfo = false }
}

@Composable
private fun InfoAction(onClick: () -> Unit) {
    val description = stringResource(R.string.peer_offramp_info_content_description)
    Box(
        modifier =
            Modifier
                .size(MIN_TOUCH_TARGET.dp)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = ZappTheme.colors.text)
    }
}

/**
 * The caret lives here rather than in the VM: rebuilding a [TextFieldValue] per recomposition resets
 * the selection to index 0, which types the handle backwards. The VM stays authoritative for the
 * text, so the stored handle it loads asynchronously still lands in the field.
 */
@Composable
private fun HandleField(field: TextFieldState, placeholder: String) {
    var input by remember { mutableStateOf(TextFieldValue()) }
    val text = field.value.getValue()
    LaunchedEffect(text) {
        if (text != input.text) {
            input = TextFieldValue(text = text, selection = TextRange(text.length))
        }
    }
    ZappInputField(
        value = input,
        onValueChange = {
            input = it
            field.onValueChange(it.text)
        },
        placeholder = placeholder,
    )
}

@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text = text,
        style = ZappTheme.typography.groupLabel.copy(color = ZappTheme.colors.textMuted),
    )
}

@Composable
private fun CurrencyChips(currencies: List<PeerCurrencyChipState>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP.dp),
        verticalArrangement = Arrangement.spacedBy(CHIP_GAP.dp),
    ) {
        currencies.forEach { chip ->
            ZappStatusChip(
                text = chip.currency.code,
                variant = if (chip.isSelected) ZappChipVariant.Accent else ZappChipVariant.Muted,
                onClick = if (chip.isToggleable) chip.onClick else null,
            )
        }
    }
}

/** Kept in the flow itself: starting a second cash-out should show what the first one is still doing. */
@Composable
private fun PeerActiveOrderRow(state: PeerActiveOrderState) {
    val c = ZappTheme.colors
    val title = state.title.getValue()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = c.accent),
                    onClick = state.onClick,
                ).semantics {
                    role = Role.Button
                    contentDescription = title
                }.padding(horizontal = ROW_PADDING.dp, vertical = ROW_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = title, style = ZappTheme.typography.rowTitle.copy(color = c.text))
            BasicText(
                text = state.subtitle.getValue(),
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
            )
        }
        Spacer(Modifier.width(GAP_SM.dp))
        Box(modifier = Modifier.size(MIN_TOUCH_TARGET.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = c.textMuted,
                modifier = Modifier.size(CHEVRON_SIZE.dp),
            )
        }
    }
}

private const val USDC_SYMBOL = "USDC"
private const val GUTTER = 18
private const val VERTICAL_PADDING = 12
private const val GAP_SM = 6
private const val GAP_LG = 16
private const val CHIP_GAP = 8
private const val ROW_PADDING = 14
private const val BOTTOM_BAR_GAP = 12
private const val CHEVRON_SIZE = 18
private const val MIN_TOUCH_TARGET = 48

@PreviewScreens
@Composable
private fun PeerCashOutPreview() =
    ZcashTheme {
        PeerCashOutView(
            state =
                PeerCashOutState(
                    platform = PeerPlatform.REVOLUT,
                    title = stringRes("Cash out to Revolut"),
                    amountInput = NumberTextFieldState(onValueChange = {}),
                    amountError = null,
                    availableBalance = stringRes("0.40"),
                    fiatEquivalent = stringRes("≈ 0.86 GBP"),
                    ledger =
                        listOf(
                            PeerLedgerRow(stringRes("Rate"), stringRes("1 USDC ≈ 0.86 GBP")),
                            PeerLedgerRow(stringRes("Typical wait"), stringRes("24 min to 2.0 h")),
                            PeerLedgerRow(stringRes("In progress"), stringRes("1.60 USDC")),
                            PeerLedgerRow(stringRes("Paid to"), stringRes("Revolut")),
                        ),
                    notice = stringRes("Enter at most 0.40 USDC"),
                    isNoticeDanger = true,
                    topUpButton = ButtonState(text = stringRes("Top up from ZEC"), onClick = {}),
                    handleField = TextFieldState(value = stringRes("andrew1abc"), onValueChange = {}),
                    handleHint = stringRes("Revtag (e.g. andrew1abc)"),
                    handleNormalized = null,
                    handleUnverified = null,
                    currencies =
                        PeerPlatform.REVOLUT.currencies.map {
                            PeerCurrencyChipState(
                                currency = it,
                                isSelected = it in PeerPlatform.REVOLUT.defaultCurrencies,
                                isToggleable = true,
                                onClick = {},
                            )
                        },
                    activeOrders =
                        listOf(
                            PeerActiveOrderState(
                                key = "a1b2",
                                title = stringRes("5.00 USDC to Revolut"),
                                subtitle = stringRes("Creating your cash-out order"),
                                onClick = {},
                            ),
                        ),
                    primaryButton = ButtonState(text = stringRes("Continue"), onClick = {}),
                    onBack = {},
                ),
        )
    }
