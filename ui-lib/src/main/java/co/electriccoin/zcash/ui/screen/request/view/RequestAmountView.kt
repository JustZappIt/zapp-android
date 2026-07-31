package co.electriccoin.zcash.ui.screen.request.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.StringResource.Companion.NUMBER_FORMAT_LOCALE
import co.electriccoin.zcash.ui.screen.request.model.AmountState
import co.electriccoin.zcash.ui.screen.request.model.MemoState
import co.electriccoin.zcash.ui.screen.request.model.OnAmount
import co.electriccoin.zcash.ui.screen.request.model.RequestCurrency
import co.electriccoin.zcash.ui.screen.request.model.RequestState
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import java.text.DecimalFormatSymbols

@Composable
internal fun RequestAmountView(
    state: RequestState.Amount,
    modifier: Modifier = Modifier
) {
    var noteFieldFocused by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(ZcashTheme.dimens.spacingDefault))

        InvalidAmountView(state.request.amountState)

        Spacer(modifier = Modifier.height(ZcashTheme.dimens.spacingDefault))

        when (state.exchangeRateState) {
            is ExchangeRateState.Data -> {
                if (state.request.amountState.currency == RequestCurrency.ZEC) {
                    RequestAmountWithMainZecView(
                        state = state,
                        onFiatPreferenceSwitch = { state.onSwitch(RequestCurrency.FIAT) },
                        modifier = Modifier.padding(horizontal = ZcashTheme.dimens.screenHorizontalSpacingRegular)
                    )
                } else {
                    RequestAmountWithMainFiatView(
                        state = state,
                        onFiatPreferenceSwitch = { state.onSwitch(RequestCurrency.ZEC) },
                        modifier = Modifier.padding(horizontal = ZcashTheme.dimens.screenHorizontalSpacingRegular)
                    )
                }
            }

            else -> {
                RequestAmountNoFiatView(
                    state = state,
                    modifier = Modifier.padding(horizontal = ZcashTheme.dimens.screenHorizontalSpacingRegular)
                )
            }
        }

        Spacer(modifier = Modifier.height(ZcashTheme.dimens.spacingDefault))

        RequestAmountNoteField(
            state = state,
            onFocusChanged = { noteFieldFocused = it },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZcashTheme.dimens.screenHorizontalSpacingRegular),
        )

        Spacer(modifier = Modifier.weight(1f))

        if (!noteFieldFocused) {
            RequestAmountKeyboardView(state = state)
            Spacer(modifier = Modifier.height(ZcashTheme.dimens.spacingLarge))
        }
    }
}

@Composable
private fun RequestAmountWithMainFiatView(
    state: RequestState.Amount,
    onFiatPreferenceSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        val exchange = state.exchangeRateState as ExchangeRateState.Data
        val fiatText =
            buildAnnotatedString {
                withStyle(style = SpanStyle(color = c.textMuted)) {
                    append((exchange.currencyConversion?.fiatCurrency ?: exchange.expectedCurrency).symbol)
                }
                append("\u2009")
                withStyle(style = SpanStyle(color = c.text)) {
                    append(
                        if (exchange.currencyConversion != null) {
                            state.request.amountState.amount
                        } else {
                            stringResource(id = R.string.request_amount_empty)
                        }
                    )
                }
            }

        AutoSizingText(
            text = fiatText,
            style =
                ZappTheme.typography.display.copy(
                    fontSize = 44.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.8).sp,
                ),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val zecText =
                buildAnnotatedString {
                    withStyle(style = SpanStyle(color = c.text)) {
                        append(
                            if (exchange.currencyConversion != null) {
                                state.request.amountState.toZecString(
                                    exchange.currencyConversion,
                                    context = context
                                )
                            } else {
                                stringResource(id = R.string.request_amount_empty)
                            }
                        )
                    }
                    append(" ")
                    withStyle(style = SpanStyle(color = c.textMuted)) {
                        append(CURRENCY_TICKER)
                    }
                }

            BasicText(
                text = zecText,
                style = ZappTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.width(10.dp))

            Image(
                painter = painterResource(id = R.drawable.ic_switch),
                contentDescription = null,
                modifier = Modifier.clickable { onFiatPreferenceSwitch() }
            )
        }
    }
}

@Composable
private fun RequestAmountWithMainZecView(
    state: RequestState.Amount,
    onFiatPreferenceSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        val exchange = state.exchangeRateState as ExchangeRateState.Data
        val zecText =
            buildAnnotatedString {
                withStyle(style = SpanStyle(color = c.text)) {
                    append(state.request.amountState.amount)
                }
                append("\u2009")
                withStyle(style = SpanStyle(color = c.textMuted)) {
                    append(CURRENCY_TICKER)
                }
            }

        AutoSizingText(
            text = zecText,
            style =
                ZappTheme.typography.display.copy(
                    fontSize = 44.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.8).sp,
                ),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val fiatText =
                buildAnnotatedString {
                    withStyle(style = SpanStyle(color = c.textMuted)) {
                        append((exchange.currencyConversion?.fiatCurrency ?: exchange.expectedCurrency).symbol)
                    }
                    append(" ")
                    withStyle(style = SpanStyle(color = c.text)) {
                        append(
                            if (exchange.currencyConversion != null) {
                                state.request.amountState.toFiatString(
                                    context,
                                    exchange.currencyConversion
                                )
                            } else {
                                stringResource(id = R.string.request_amount_empty)
                            }
                        )
                    }
                }

            BasicText(
                text = fiatText,
                style = ZappTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.width(10.dp))

            Image(
                painter = painterResource(id = R.drawable.ic_switch),
                contentDescription = null,
                modifier = Modifier.clickable { onFiatPreferenceSwitch() }
            )
        }
    }
}

@Composable
private fun RequestAmountNoFiatView(
    state: RequestState.Amount,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        val text =
            buildAnnotatedString {
                withStyle(style = SpanStyle(color = c.text)) {
                    append(state.request.amountState.amount)
                }
                append("\u2009")
                withStyle(style = SpanStyle(color = c.textMuted)) {
                    append(CURRENCY_TICKER)
                }
            }

        AutoSizingText(
            text = text,
            style =
                ZappTheme.typography.display.copy(
                    fontSize = 44.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.8).sp,
                ),
        )
    }
}

@Composable
private fun RequestAmountNoteField(
    state: RequestState.Amount,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val memoText = state.request.memoState.text
    Row(
        modifier =
            modifier
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = null,
            tint = c.textSubtle,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        BasicTextField(
            value = memoText,
            onValueChange = { state.onMemo(MemoState.new(it, state.request.memoState.zecAmount)) },
            singleLine = true,
            textStyle = ZappTheme.typography.body.copy(color = c.text),
            modifier =
                Modifier
                    .weight(1f)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
            decorationBox = { innerTextField ->
                if (memoText.isEmpty()) {
                    BasicText(
                        text = stringResource(id = R.string.request_amount_note_hint),
                        style = ZappTheme.typography.body.copy(color = c.textSubtle),
                    )
                }
                innerTextField()
            },
        )
    }
}

private val KEYBOARD_ROWS =
    listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(null, "0", "⌫"),
    )

@Composable
private fun RequestAmountKeyboardView(
    state: RequestState.Amount,
    modifier: Modifier = Modifier
) {
    val c = ZappTheme.colors
    val decimalSep = DecimalFormatSymbols(NUMBER_FORMAT_LOCALE).decimalSeparator.toString()

    // Replace the null slot with the locale decimal separator
    val rows =
        KEYBOARD_ROWS.map { row ->
            row.map { key -> if (key == null) decimalSep else key }
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(60.dp)
                                .border(1.dp, c.border, RectangleShape)
                                .clickable {
                                    when (key) {
                                        "⌫" -> state.onAmount(OnAmount.Delete)
                                        decimalSep -> state.onAmount(OnAmount.Separator(decimalSep))
                                        else -> state.onAmount(OnAmount.Number(key.toInt()))
                                    }
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = key,
                            style =
                                ZappTheme.typography.button.copy(
                                    color = c.text,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Black,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InvalidAmountView(
    amountState: AmountState,
    modifier: Modifier = Modifier
) {
    val c = ZappTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .requiredHeight(48.dp)
                .padding(horizontal = ZcashTheme.dimens.screenHorizontalSpacingRegular),
    ) {
        if (amountState.isValid == false) {
            Image(
                painter = painterResource(id = R.drawable.ic_alert_outline),
                contentDescription = null
            )

            Spacer(modifier = Modifier.width(8.dp))

            BasicText(
                text = stringResource(id = R.string.request_amount_invalid),
                style =
                    ZappTheme.typography.caption.copy(
                        color = c.accent,
                        fontWeight = FontWeight.Medium,
                    ),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}
