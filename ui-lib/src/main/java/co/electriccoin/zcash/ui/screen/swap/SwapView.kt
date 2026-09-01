package co.electriccoin.zcash.ui.screen.swap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.appbar.ZashiMainTopAppBarState
import co.electriccoin.zcash.ui.design.component.AssetCardState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.ChipButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.component.ZashiAddressTextField
import co.electriccoin.zcash.ui.design.component.ZashiChipButton
import co.electriccoin.zcash.ui.design.component.ZashiHorizontalDivider
import co.electriccoin.zcash.ui.design.component.ZashiIconButton
import co.electriccoin.zcash.ui.design.component.ZashiImageButton
import co.electriccoin.zcash.ui.design.component.ZashiInfoText
import co.electriccoin.zcash.ui.design.component.listitem.SimpleListItemState
import co.electriccoin.zcash.ui.design.component.listitem.ZashiSimpleListItem
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ellipsizeAddress
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.fixture.ZashiMainTopAppBarStateFixture
import co.electriccoin.zcash.ui.screen.swap.SwapState.AddressLocation.BOTTOM
import co.electriccoin.zcash.ui.screen.swap.SwapState.AddressLocation.TOP
import co.electriccoin.zcash.ui.screen.swap.ui.SwapAmountText
import co.electriccoin.zcash.ui.screen.swap.ui.SwapAmountTextField
import co.electriccoin.zcash.ui.screen.swap.ui.SwapAmountTextFieldState
import co.electriccoin.zcash.ui.screen.swap.ui.SwapAmountTextState

@Composable
internal fun SwapView(
    state: SwapState,
    appBarState: ZashiMainTopAppBarState,
    onSideEffect: (amountFocusRequester: FocusRequester) -> Unit = { },
    embeddedInTabHost: Boolean = false,
) {
    val amountFocusRequester = remember { FocusRequester() }
    val c = ZappTheme.colors

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .then(
                    if (embeddedInTabHost) {
                        Modifier
                    } else {
                        Modifier.windowInsetsPadding(
                            WindowInsets.statusBars.union(WindowInsets.displayCutout),
                        )
                    },
                ),
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        if (!embeddedInTabHost) {
            ZappScreenHeader(
                title = stringResource(R.string.swap_title),
                right = {
                    ZashiIconButton(state.swapInfoButton)
                },
            )
        }

        // ── Scrollable body ─────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                // Balance header
                SwapBalanceHeader(state)
                Spacer(modifier = Modifier.height(12.dp))

                // "You send" label
                BasicText(
                    text = stringResource(R.string.swap_you_send),
                    style = ZappTheme.typography.eyebrow.copy(color = c.textMuted),
                )
                Spacer(modifier = Modifier.height(4.dp))
                SwapAmountTextField(
                    state = state.amountTextField,
                    focusRequester = amountFocusRequester,
                )

                // Address field TOP (SWAP_INTO_ZEC refund)
                if (state.addressLocation == TOP) {
                    Spacer(modifier = Modifier.height(10.dp))
                    AddressTextField(state = state)
                }

                // Swap direction toggle
                SwapDirectionToggle(state)

                // "You receive" label
                BasicText(
                    text = stringResource(R.string.swap_you_receive),
                    style = ZappTheme.typography.eyebrow.copy(color = c.textMuted),
                )
                Spacer(modifier = Modifier.height(4.dp))
                SwapAmountText(state = state.amountText)

                // Rate info
                if (state.infoItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    state.infoItems.forEach { ZashiSimpleListItem(state = it) }
                }

                // Receiving address (BOTTOM = SWAP_FROM_ZEC destination address)
                if (state.addressLocation == BOTTOM) {
                    Spacer(modifier = Modifier.height(10.dp))
                    AddressTextField(state = state)
                } else {
                    // Compact read-only "Receiving to" row (SWAP_INTO_ZEC)
                    Spacer(modifier = Modifier.height(10.dp))
                    ReceivingToRow(state)
                }

                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.height(8.dp))

                if (state.errorFooter != null) {
                    SwapErrorFooter(state.errorFooter)
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (state.infoFooter != null) {
                    ZashiInfoText(text = state.infoFooter.getValue())
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        // ── Bottom action bar ────────────────────────────────────────────────
        SwapBottomBar(state)

        SideEffect { onSideEffect(amountFocusRequester) }
    }
}

@Composable
private fun SwapBalanceHeader(state: SwapState) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        BasicText(
            text = stringResource(R.string.swap_available_to_swap),
            style = t.caption.copy(color = c.textMuted),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = state.headerBalance?.getValue() ?: "—",
                style = t.display.copy(color = c.text),
            )
            Spacer(modifier = Modifier.width(6.dp))
            BasicText(
                text = stringResource(cash.z.ecc.sdk.ext.R.string.zcash_token_zec),
                style = t.display.copy(color = c.accent),
            )
        }
        state.headerBalanceFiat?.let { fiat ->
            Spacer(modifier = Modifier.height(2.dp))
            BasicText(
                text = fiat.getValue(),
                style = t.caption.copy(color = c.textMuted),
            )
        }
        state.priceStats?.let { stats ->
            Spacer(modifier = Modifier.height(8.dp))
            SwapPriceStatsRow(stats)
        }
    }
}

@Composable
private fun SwapPriceStatsRow(stats: SwapPriceStats) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = stringResource(R.string.swap_stats_price),
                style = t.caption.copy(color = c.textMuted),
            )
            BasicText(
                text = stats.price.getValue(),
                style = t.caption.copy(color = c.text, fontWeight = FontWeight.SemiBold),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Box(
            modifier =
                Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(c.border),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = stringResource(R.string.swap_stats_fee),
                style = t.caption.copy(color = c.textMuted),
            )
            BasicText(
                text = stats.fee.getValue(),
                style = t.caption.copy(color = c.text, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun SwapDirectionToggle(state: SwapState) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        ZashiImageButton(state = state.changeModeButton)
    }
}

@Composable
private fun ReceivingToRow(state: SwapState) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    val address = state.receivingZecAddress?.getValue().orEmpty()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surfaceAlt)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(c.accent),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = stringResource(R.string.swap_receiving_to),
                style = t.caption.copy(color = c.textMuted),
            )
            BasicText(
                text = if (address.isNotBlank()) address.ellipsizeAddress() else "—",
                style = t.mono.copy(color = c.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.onChangeReceivingAddress?.let { onClick ->
            Spacer(modifier = Modifier.width(8.dp))
            BasicText(
                text = stringResource(R.string.swap_change_address),
                style = t.button.copy(color = c.accent),
                modifier =
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = c.accent),
                        onClick = onClick,
                    ),
            )
        }
    }
}

@Composable
private fun SwapBottomBar(state: SwapState) {
    ZappBottomActionBar(
        onBack = state.onBack,
        primaryAction = {
            Row(
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SlippageButton(state.slippage)
                // Top Up takes precedence when balance is zero
                when {
                    state.topUpButton != null -> {
                        ZappButton(
                            text = state.topUpButton.text.getValue(),
                            modifier = Modifier.weight(1f),
                            variant = ZappButtonVariant.Primary,
                            onClick = state.topUpButton.onClick,
                        )
                    }

                    state.primaryButton != null -> {
                        ZappButton(
                            text = state.primaryButton.text.getValue(),
                            enabled = state.primaryButton.isEnabled,
                            variant =
                                if (state.primaryButton.style == ButtonStyle.DESTRUCTIVE1) {
                                    ZappButtonVariant.Danger
                                } else {
                                    ZappButtonVariant.Primary
                                },
                            modifier = Modifier.weight(1f),
                            onClick = state.primaryButton.onClick,
                        )
                    }
                }
            }
        },
    )
}

/** Public: also used by UnifiedSendView. */
@Composable
fun SlippageButton(state: ButtonState) {
    val c = ZappTheme.colors
    val label = stringResource(R.string.swap_slippage_tolerance)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            Modifier
                .size(52.dp)
                .background(c.surface)
                .border(BorderStroke(1.dp, c.border))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = c.text),
                    enabled = state.isEnabled,
                    onClick = state.onClick,
                ).semantics {
                    contentDescription = label
                    role = Role.Button
                },
    ) {
        state.trailingIcon?.let { icon ->
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(c.text),
            )
        }
        BasicText(
            text = state.text.getValue(),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
    }
}

@Composable
fun SwapErrorFooter(errorFooter: SwapErrorFooterState) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    Column {
        Image(
            modifier =
                Modifier
                    .size(16.dp)
                    .align(Alignment.CenterHorizontally),
            painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
            contentDescription = null,
            colorFilter = ColorFilter.tint(c.danger),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = errorFooter.title.getValue(),
            style = t.caption,
            fontWeight = FontWeight.Medium,
            color = c.danger,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = errorFooter.subtitle.getValue(),
            style = t.caption,
            color = c.danger,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ColumnScope.AddressTextField(state: SwapState) {
    Row(
        modifier =
            Modifier then
                if (state.onAddressClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        onClick = state.onAddressClick,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    )
                },
        verticalAlignment = CenterVertically,
    ) {
        Text(
            text =
                when (state.addressLocation) {
                    TOP -> stringResource(R.string.swap_refund_address)
                    BOTTOM -> stringResource(co.electriccoin.zcash.ui.design.R.string.general_address)
                },
            style = ZappTheme.typography.caption,
            fontWeight = FontWeight.Medium,
        )
        if (state.onAddressClick != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Image(
                modifier = Modifier.size(16.dp),
                painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
                contentDescription = null,
            )
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    ZashiAddressTextField(
        state = state.address,
        modifier =
            Modifier
                .fillMaxWidth()
                .onKeyEvent {
                    if (state.addressContact != null && it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DEL) {
                        state.addressContact.onClick()
                        true
                    } else {
                        false
                    }
                },
        placeholder =
            if (state.addressContact == null) {
                {
                    Text(
                        text = state.addressPlaceholder.getValue(),
                        style = ZappTheme.typography.body,
                        color = ZappTheme.colors.textSubtle,
                    )
                }
            } else {
                null
            },
        prefix =
            if (state.addressContact == null) {
                null
            } else {
                {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .padding(top = 3.5.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        ZashiChipButton(
                            state.addressContact,
                            contentPadding = PaddingValues(start = 10.dp, top = 4.5.dp, end = 4.5.dp, bottom = 4.5.dp),
                            useTint = false,
                            shape = RoundedCornerShape(0.dp),
                            color = ZappTheme.colors.surfaceAlt,
                            border = BorderStroke(1.dp, ZappTheme.colors.border),
                            textStyle =
                                ZappTheme.typography.caption.copy(
                                    color = ZappTheme.colors.text,
                                    fontWeight = FontWeight.Medium,
                                ),
                        )
                    }
                }
            },
        suffix = {
            Row(verticalAlignment = Alignment.Top) {
                ZashiImageButton(
                    modifier = Modifier.size(36.dp),
                    state = state.addressBookButton,
                )
                Spacer(modifier = Modifier.width(4.dp))
                ZashiImageButton(
                    modifier = Modifier.size(36.dp),
                    state = state.qrScannerButton,
                )
            }
        },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
    )
}

@PreviewScreens
@Composable
private fun Preview() {
    ZcashTheme {
        SwapView(
            state =
                SwapState(
                    headerBalance = stringRes("12.450"),
                    headerBalanceFiat = stringRes("≈ $353.58"),
                    priceStats =
                        SwapPriceStats(
                            price = stringRes("$28.40"),
                            fee = stringRes("~1.0%"),
                        ),
                    swapInfoButton = IconButtonState(R.drawable.ic_help) {},
                    amountTextField =
                        SwapAmountTextFieldState(
                            title = stringRes("From"),
                            error = null,
                            token =
                                AssetCardState.Data(
                                    token = stringRes("USDT"),
                                    bigIcon = null,
                                    smallIcon = null,
                                    isEnabled = false,
                                    onClick = {},
                                ),
                            textFieldPrefix = imageRes(R.drawable.ic_send_zashi),
                            textField = NumberTextFieldState {},
                            secondaryText = stringResByDynamicCurrencyNumber(100, "USDT"),
                            max =
                                ButtonState(
                                    stringResByDynamicCurrencyNumber(100, "$"),
                                ),
                            onSwapChange = {},
                        ),
                    slippage =
                        ButtonState(
                            stringRes("1%"),
                            trailingIcon = R.drawable.ic_swap_slippage,
                        ),
                    amountText =
                        SwapAmountTextState(
                            token =
                                AssetCardState.Data(
                                    token = stringRes("ZEC"),
                                    bigIcon = null,
                                    smallIcon = null,
                                    isEnabled = false,
                                    onClick = {},
                                ),
                            title = stringRes("To"),
                            text = stringResByDynamicCurrencyNumber(101, "$"),
                            secondaryText = stringResByDynamicCurrencyNumber(2.47123, "ZEC"),
                            subtitle = null,
                        ),
                    infoItems =
                        listOf(
                            SimpleListItemState(
                                title = stringRes("Rate"),
                                text = stringRes("1 ZEC = 51.74 USDC"),
                            ),
                        ),
                    addressContact =
                        ChipButtonState(
                            text = stringRes("Contact"),
                            onClick = {},
                            endIcon = co.electriccoin.zcash.ui.design.R.drawable.ic_chip_close,
                        ),
                    addressBookButton =
                        IconButtonState(
                            icon = R.drawable.send_address_book,
                            onClick = {},
                        ),
                    addressLocation = BOTTOM,
                    onAddressClick = null,
                    address = TextFieldState(stringRes("")) {},
                    addressPlaceholder = stringRes(co.electriccoin.zcash.ui.design.R.string.general_enter_address),
                    qrScannerButton =
                        IconButtonState(
                            icon = R.drawable.qr_code_icon,
                            onClick = {},
                        ),
                    errorFooter = null,
                    primaryButton =
                        ButtonState(
                            stringRes("Get a quote"),
                        ),
                    topUpButton = null,
                    infoFooter =
                        stringRes(
                            "NEAR only supports swaps to a transparent address. " +
                                "Zashi will prompt you to shield your funds upon receipt.",
                        ),
                    onBack = {},
                    changeModeButton = IconButtonState(R.drawable.ic_swap_change_mode) {},
                    receivingZecAddress = null,
                    onChangeReceivingAddress = null,
                ),
            appBarState = ZashiMainTopAppBarStateFixture.new(),
        )
    }
}

@PreviewScreens
@Composable
private fun TopUpPreview() {
    ZcashTheme {
        SwapView(
            state =
                SwapState(
                    headerBalance = stringRes("0.000"),
                    headerBalanceFiat = stringRes("≈ $0.00"),
                    priceStats =
                        SwapPriceStats(
                            price = stringRes("$382.77"),
                            fee = stringRes("~2%"),
                        ),
                    swapInfoButton = IconButtonState(R.drawable.ic_help) {},
                    amountTextField =
                        SwapAmountTextFieldState(
                            title = stringRes("From"),
                            error = null,
                            token =
                                AssetCardState.Data(
                                    token = stringRes("ZEC"),
                                    bigIcon = null,
                                    smallIcon = null,
                                    isEnabled = false,
                                    onClick = {},
                                ),
                            textFieldPrefix = imageRes(R.drawable.ic_send_zashi),
                            textField = NumberTextFieldState {},
                            secondaryText = stringResByDynamicCurrencyNumber(0, "$"),
                            max = null,
                            onSwapChange = {},
                        ),
                    slippage =
                        ButtonState(
                            stringRes("2%"),
                            trailingIcon = R.drawable.ic_swap_slippage,
                        ),
                    amountText =
                        SwapAmountTextState(
                            token =
                                AssetCardState.Data(
                                    token = stringRes("ZEC"),
                                    bigIcon = null,
                                    smallIcon = null,
                                    isEnabled = false,
                                    onClick = {},
                                ),
                            title = stringRes("To"),
                            text = stringRes("0.00"),
                            secondaryText = stringResByDynamicCurrencyNumber(0, "$"),
                            subtitle = null,
                        ),
                    infoItems =
                        listOf(
                            SimpleListItemState(
                                title = stringRes("Rate"),
                                text = stringRes("1 ZEC = 1.00 ZEC"),
                            ),
                        ),
                    addressBookButton =
                        IconButtonState(
                            icon = R.drawable.send_address_book,
                            onClick = {},
                        ),
                    addressLocation = BOTTOM,
                    onAddressClick = null,
                    address = TextFieldState(stringRes("")) {},
                    addressPlaceholder = stringRes(co.electriccoin.zcash.ui.design.R.string.general_enter_address),
                    qrScannerButton =
                        IconButtonState(
                            icon = R.drawable.qr_code_icon,
                            onClick = {},
                        ),
                    errorFooter = null,
                    primaryButton = null,
                    topUpButton = ButtonState(stringRes("Top Up")),
                    infoFooter = null,
                    onBack = {},
                    changeModeButton = IconButtonState(R.drawable.ic_swap_change_mode) {},
                    receivingZecAddress = null,
                    onChangeReceivingAddress = null,
                ),
            appBarState = ZashiMainTopAppBarStateFixture.new(),
        )
    }
}

@PreviewScreens
@Composable
private fun ServiceUnavailableErrorPreview() {
    ZcashTheme {
        SwapView(
            state =
                SwapState(
                    headerBalance = null,
                    headerBalanceFiat = null,
                    priceStats = null,
                    swapInfoButton = IconButtonState(R.drawable.ic_help) {},
                    amountTextField =
                        SwapAmountTextFieldState(
                            title = stringRes("From"),
                            error = null,
                            token =
                                AssetCardState.Data(
                                    token = stringRes("USDT"),
                                    bigIcon = null,
                                    smallIcon = null,
                                    isEnabled = false,
                                    onClick = {},
                                ),
                            textFieldPrefix = imageRes(R.drawable.ic_send_zashi),
                            textField = NumberTextFieldState {},
                            secondaryText = stringResByDynamicCurrencyNumber(100, "USDT"),
                            max =
                                ButtonState(
                                    stringResByDynamicCurrencyNumber(100, "$"),
                                ),
                            onSwapChange = {},
                        ),
                    slippage =
                        ButtonState(
                            stringRes("1%"),
                            trailingIcon = R.drawable.ic_swap_slippage,
                        ),
                    amountText =
                        SwapAmountTextState(
                            token =
                                AssetCardState.Data(
                                    token = stringRes("ZEC"),
                                    bigIcon = null,
                                    smallIcon = null,
                                    isEnabled = false,
                                    onClick = {},
                                ),
                            title = stringRes("To"),
                            text = stringResByDynamicCurrencyNumber(101, "$"),
                            secondaryText = stringResByDynamicCurrencyNumber(2.47123, "ZEC"),
                            subtitle = null,
                        ),
                    infoItems =
                        listOf(
                            SimpleListItemState(
                                title = stringRes("Rate"),
                                text = stringRes("1 ZEC = 51.74 USDC"),
                            ),
                        ),
                    addressBookButton =
                        IconButtonState(
                            icon = R.drawable.send_address_book,
                            onClick = {},
                        ),
                    addressLocation = BOTTOM,
                    onAddressClick = null,
                    address = TextFieldState(stringRes("")) {},
                    addressPlaceholder = stringRes(co.electriccoin.zcash.ui.design.R.string.general_enter_address),
                    qrScannerButton =
                        IconButtonState(
                            icon = R.drawable.qr_code_icon,
                            onClick = {},
                        ),
                    errorFooter =
                        SwapErrorFooterState(
                            title = stringRes("The service is unavailable"),
                            subtitle = stringRes("Please try again later."),
                        ),
                    primaryButton = null,
                    topUpButton = null,
                    infoFooter = null,
                    onBack = {},
                    changeModeButton = IconButtonState(R.drawable.ic_swap_change_mode) {},
                    receivingZecAddress = null,
                    onChangeReceivingAddress = null,
                ),
            appBarState = ZashiMainTopAppBarStateFixture.new(),
        )
    }
}
