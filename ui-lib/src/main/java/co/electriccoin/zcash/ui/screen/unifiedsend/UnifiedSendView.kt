package co.electriccoin.zcash.ui.screen.unifiedsend

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.animation.shake
import co.electriccoin.zcash.ui.design.component.AssetCardState
import co.electriccoin.zcash.ui.design.component.ChipButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiAddressTextField
import co.electriccoin.zcash.ui.design.component.ZashiAssetCard
import co.electriccoin.zcash.ui.design.component.ZashiChipButton
import co.electriccoin.zcash.ui.design.component.ZashiImageButton
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextField
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextFieldDefaults
import co.electriccoin.zcash.ui.design.component.ZashiTextField
import co.electriccoin.zcash.ui.design.component.ZashiTextFieldDefaults
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.swap.SlippageButton
import co.electriccoin.zcash.ui.screen.swap.SwapErrorFooter

@Composable
internal fun UnifiedSendView(
    state: UnifiedSendState,
    balanceState: BalanceWidgetState,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .imePadding(),
    ) {
        ZappScreenHeader(
            title = stringResource(R.string.unified_send_title),
            containerColor = c.bg,
            titleStyle = ZappTheme.typography.displaySecondary,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 8.dp),
            ) {
                // Balance widget with title
                Spacer(8.dp)
                BasicText(
                    text = stringResource(R.string.unified_send_available_balance),
                    style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
                    modifier = Modifier.align(CenterHorizontally),
                )
                Spacer(4.dp)
                SendBalanceHeader(
                    state = balanceState,
                    modifier = Modifier.align(CenterHorizontally),
                )
                Spacer(32.dp)

                // ── Sentence: "I want to send" ──────────────────────────────
                SentenceFragment(stringResource(R.string.unified_send_sentence_i_want_to_send))
                Spacer(8.dp)
                AddressField(state)
                AnimatedVisibility(visible = state.isABHintVisible) {
                    Column {
                        Spacer(8.dp)
                        SendAddressBookHint(Modifier.fillMaxWidth())
                    }
                }
                Spacer(20.dp)

                // ── Sentence: "who will receive" ────────────────────────────
                SentenceFragment(
                    text = stringResource(R.string.unified_send_asset),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(8.dp)
                ZashiAssetCard(
                    state.asset,
                    modifier = Modifier.align(CenterHorizontally).widthIn(max = 280.dp),
                )
                Spacer(20.dp)

                // ── Sentence: "I'll pay" ─────────────────────────────────────
                SentenceFragment(stringResource(R.string.unified_send_sentence_ill_pay))
                Spacer(8.dp)
                val amountErrorText = state.amountError?.getValue()
                var lastAmountErrorText by remember { mutableStateOf(amountErrorText.orEmpty()) }
                val haptic = LocalHapticFeedback.current
                LaunchedEffect(amountErrorText) {
                    if (amountErrorText != null) {
                        lastAmountErrorText = amountErrorText
                        runCatching { haptic.performHapticFeedback(HapticFeedbackType.Reject) }
                    }
                }
                Box(modifier = Modifier.shake(amountErrorText)) {
                    AmountFields(state)
                }
                AnimatedVisibility(visible = amountErrorText != null) {
                    Column {
                        Spacer(6.dp)
                        Text(
                            text = lastAmountErrorText,
                            style = ZappTheme.typography.caption,
                            color = ZappTheme.colors.danger,
                        )
                    }
                }
                Spacer(12.dp)

                // ── Swap mode: "They receive ≈ X TOKEN" ─────────────────────
                if (state.theyReceiveLabel != null) {
                    TheyReceiveRow(state.theyReceiveLabel)
                    Spacer(8.dp)
                }

                // ── Swap mode: Slippage ──────────────────────────────────────
                if (state.slippage != null && state.onSlippageClick != null) {
                    SlippageButton(
                        state =
                            co.electriccoin.zcash.ui.design.component.ButtonState(
                                text = state.slippage,
                                icon = R.drawable.ic_swap_slippage,
                                onClick = state.onSlippageClick,
                            )
                    )
                    Spacer(8.dp)
                }

                // ── ZEC-direct mode: Memo ────────────────────────────────────
                val memo = state.memo
                AnimatedVisibility(visible = memo != null) {
                    if (memo is MemoFieldState.Editable) MemoSection(memo)
                }

                // ── Error footer (service unavailable etc.) ──────────────────
                if (state.errorFooter != null) {
                    Spacer(1f)
                    Spacer(12.dp)
                    SwapErrorFooter(state.errorFooter)
                    Spacer(24.dp)
                } else {
                    Spacer(1f)
                    Spacer(12.dp)
                }
            }
        }

        if (state.infoFooter != null) {
            Text(
                text = state.infoFooter.getValue(),
                style = ZappTheme.typography.caption,
                color = ZappTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ZappBackButton(onClick = state.onBack)
            CtaButton(btn = state.primaryButton, modifier = Modifier.weight(1f).padding(start = 12.dp))
        }
    }
}

@Composable
private fun SentenceFragment(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    BasicText(
        text = text,
        style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted, textAlign = textAlign),
        modifier = modifier,
    )
}

@Composable
private fun CtaButton(btn: PrimaryButtonState, modifier: Modifier = Modifier) {
    when (btn) {
        is PrimaryButtonState.Review -> {
            ZappButton(
                modifier = modifier,
                text = stringResource(R.string.send_create),
                enabled = !btn.isLoading,
                onClick = btn.onClick,
            )
        }

        is PrimaryButtonState.TopUp -> {
            ZappButton(
                modifier = modifier,
                text = stringResource(R.string.unified_send_top_up),
                onClick = btn.onClick,
            )
        }

        PrimaryButtonState.Disabled -> {
            ZappButton(
                modifier = modifier,
                text = stringResource(R.string.send_create),
                enabled = false,
                onClick = {},
            )
        }
    }
}

@Composable
private fun TheyReceiveRow(label: StringResource) {
    Row(verticalAlignment = CenterVertically) {
        Image(
            modifier = Modifier.size(20.dp),
            painter = painterResource(R.drawable.ic_zec_round_full),
            contentDescription = null
        )
        Spacer(8.dp)
        Text(
            text = label.getValue(),
            style = ZappTheme.typography.caption,
            fontWeight = FontWeight.Medium,
            color = ZappTheme.colors.text,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AmountFields(state: UnifiedSendState) {
    val primarySource = remember { MutableInteractionSource() }
    val isPrimaryFocused by primarySource.collectIsFocusedAsState()
    val fiatCurrency = state.fiatCurrency

    if (fiatCurrency == null) {
        AmountInputField(
            state = state.zecAmount,
            fiatCurrency = null,
            isSecondary = false,
            interactionSource = primarySource,
            showZecPlaceholder = !isPrimaryFocused,
            showZecPrefix = true,
            fiatValue = "",
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    val isFiatPrimary = state.isAmountSwapped
    val primaryState = if (isFiatPrimary) state.fiatAmount else state.zecAmount
    val secondaryValue =
        (if (isFiatPrimary) state.zecAmount else state.fiatAmount)
            .innerState.innerTextFieldState.value
            .getValue()
    val secondaryUnit = if (isFiatPrimary) "ZEC" else fiatCurrency.code
    val secondaryAmount =
        if (isFiatPrimary) {
            secondaryValue.ifEmpty { "0.00" }
        } else {
            fiatCurrency.symbol + secondaryValue.ifEmpty { "0.00" }
        }
    val estimatedEquivalent =
        stringResource(
            R.string.unified_send_estimated_equivalent,
            secondaryAmount,
            secondaryUnit,
        )

    Column(modifier = Modifier.fillMaxWidth()) {
        AmountInputField(
            state = primaryState,
            fiatCurrency = fiatCurrency.takeIf { isFiatPrimary },
            isSecondary = false,
            interactionSource = primarySource,
            showZecPlaceholder = !isFiatPrimary && !isPrimaryFocused,
            showZecPrefix = !isFiatPrimary,
            fiatValue =
                state.fiatAmount.innerState.innerTextFieldState.value
                    .getValue(),
            modifier = Modifier.fillMaxWidth(),
            suffix = {
                Image(
                    painter = painterResource(R.drawable.ic_send_convert),
                    contentDescription = stringResource(R.string.unified_send_swap_amounts),
                    colorFilter = ColorFilter.tint(color = ZappTheme.colors.text),
                    modifier = Modifier.size(40.dp).clickable(onClick = state.onAmountSwap).padding(8.dp),
                )
            },
        )
        Spacer(10.dp)
        BasicText(
            text = estimatedEquivalent,
            style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textSubtle, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AmountInputField(
    state: NumberTextFieldState,
    fiatCurrency: FiatCurrency?,
    isSecondary: Boolean,
    interactionSource: MutableInteractionSource?,
    showZecPlaceholder: Boolean,
    fiatValue: String,
    showZecPrefix: Boolean = false,
    modifier: Modifier = Modifier,
    suffix: @Composable (() -> Unit)? = null,
) {
    val amountStyle =
        if (isSecondary) {
            ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted)
        } else {
            ZappTheme.typography.rowTitle.copy(color = ZappTheme.colors.text, fontWeight = FontWeight.Black)
        }
    ZashiNumberTextField(
        modifier = modifier,
        state = state,
        textStyle = amountStyle,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        suffix = suffix,
        placeholder =
            when {
                fiatCurrency != null -> {
                    {
                        ZashiNumberTextFieldDefaults.Placeholder(
                            modifier = Modifier.fillMaxWidth(),
                            style = amountStyle.copy(color = ZappTheme.colors.textSubtle),
                            fontWeight = FontWeight.Normal,
                            text = fiatCurrency.code
                        )
                    }
                }

                showZecPlaceholder -> {
                    {
                        ZashiNumberTextFieldDefaults.Placeholder(
                            modifier = Modifier.fillMaxWidth(),
                            style = amountStyle.copy(color = ZappTheme.colors.textSubtle),
                            fontWeight = FontWeight.Normal,
                            text = "0.00"
                        )
                    }
                }

                else -> {
                    null
                }
            },
        prefix =
            if (fiatCurrency != null || showZecPrefix) {
                {
                    BasicText(
                        text = fiatCurrency?.symbol ?: "ZEC",
                        style =
                            amountStyle.copy(
                                color =
                                    if (showZecPrefix || fiatValue.isNotEmpty()) {
                                        ZappTheme.colors.text
                                    } else {
                                        ZappTheme.colors.textMuted
                                    }
                            ),
                    )
                }
            } else {
                null
            },
    )
}

@Composable
private fun AddressField(state: UnifiedSendState) {
    ZashiAddressTextField(
        state = state.address,
        modifier =
            Modifier
                .fillMaxWidth()
                .onKeyEvent {
                    if (state.abContact != null && it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DEL) {
                        state.abContact.onClick()
                        true
                    } else {
                        false
                    }
                },
        placeholder =
            if (state.abContact == null) {
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
            if (state.abContact == null) {
                null
            } else {
                { ContactChipPrefix(state.abContact) }
            },
        suffix = {
            Row(verticalAlignment = Alignment.Top) {
                ZashiImageButton(modifier = Modifier.size(36.dp), state = state.abButton)
                Spacer(4.dp)
                ZashiImageButton(modifier = Modifier.size(36.dp), state = state.qrButton)
            }
        },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
    )
}

@Composable
private fun ContactChipPrefix(contact: ChipButtonState) {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .padding(top = 3.5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        ZashiChipButton(
            contact,
            contentPadding = PaddingValues(start = 10.dp, top = 4.5.dp, end = 4.5.dp, bottom = 4.5.dp),
            useTint = false,
            shape = RoundedCornerShape(0.dp),
            color = ZappTheme.colors.chipBg,
            border = BorderStroke(1.dp, ZappTheme.colors.border),
            textStyle =
                ZappTheme.typography.caption.copy(
                    color = ZappTheme.colors.text,
                    fontWeight = FontWeight.Medium,
                )
        )
    }
}

@Composable
private fun MemoSection(memo: MemoFieldState.Editable) {
    Column {
        Spacer(12.dp)
        BasicText(
            text = stringResource(R.string.unified_send_memo_hint),
            style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
        )
        Spacer(8.dp)
        ZashiTextField(
            value = memo.text,
            onValueChange = memo.onValueChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(88.dp),
            singleLine = false,
            maxLines = 4,
            isEnabled = memo.isEnabled,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
            innerModifier = ZashiTextFieldDefaults.innerModifier,
            placeholder = null,
            suffix = {
                Text(
                    text = "${memo.byteCount} / ${memo.maxBytes}",
                    style = ZappTheme.typography.caption,
                    color =
                        if (memo.byteCount > memo.maxBytes) {
                            ZappTheme.colors.danger
                        } else {
                            ZappTheme.colors.textSubtle
                        },
                )
            },
        )
    }
}
