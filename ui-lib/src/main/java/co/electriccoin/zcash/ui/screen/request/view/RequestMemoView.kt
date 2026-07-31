package co.electriccoin.zcash.ui.screen.request.view

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.model.Memo
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiTextField
import co.electriccoin.zcash.ui.design.component.ZashiTextFieldDefaults
import co.electriccoin.zcash.ui.design.component.zapp.ZappChipVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappStatusChip
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.request.model.MemoState
import co.electriccoin.zcash.ui.screen.request.model.RequestState
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER

@Composable
internal fun RequestMemoView(
    state: RequestState.Memo,
    modifier: Modifier = Modifier
) {
    val c = ZappTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(20.dp))

        ZappStatusChip(
            text = stringResource(id = R.string.request_privacy_level_shielded).uppercase(),
            variant = ZappChipVariant.Success,
            dotColor = c.success,
        )

        Spacer(Modifier.height(16.dp))

        BasicText(
            text = stringResource(id = R.string.request_memo_payment_request_subtitle).uppercase(),
            style =
                ZappTheme.typography.eyebrow.copy(
                    color = c.textSubtle,
                    fontSize = 10.sp,
                    letterSpacing = 1.8.sp,
                    fontWeight = FontWeight.Black,
                ),
        )

        Spacer(Modifier.height(14.dp))

        RequestMemoZecAmountView(state = state)

        Spacer(Modifier.height(28.dp))

        RequestMemoTextField(state = state)

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RequestMemoZecAmountView(
    state: RequestState.Memo,
    modifier: Modifier = Modifier
) {
    val c = ZappTheme.colors
    val zecText =
        buildAnnotatedString {
            withStyle(style = SpanStyle(color = c.text)) {
                append(state.request.memoState.zecAmount)
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
                color = c.text,
                fontSize = 44.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.8).sp,
            ),
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RequestMemoTextField(
    state: RequestState.Memo,
    modifier: Modifier = Modifier
) {
    val memoState = state.request.memoState
    val focusRequester = remember { FocusRequester() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val c = ZappTheme.colors

    Column(
        modifier =
            modifier
                .animateContentSize()
                .bringIntoViewRequester(bringIntoViewRequester)
                .focusRequester(focusRequester),
    ) {
        ZashiTextField(
            minLines = 3,
            value = memoState.text,
            error = if (memoState.isValid()) null else "",
            onValueChange = {
                state.onMemo(MemoState.new(it, memoState.zecAmount))
            },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default,
                    capitalization = KeyboardCapitalization.Sentences,
                ),
            placeholder = {
                Text(
                    text = stringResource(id = R.string.request_memo_text_field_hint),
                    color = c.textSubtle,
                )
            },
            colors =
                if (memoState.isValid()) {
                    ZashiTextFieldDefaults.defaultColors()
                } else {
                    ZashiTextFieldDefaults.defaultColors(
                        disabledTextColor = c.textMuted,
                        disabledHintColor = c.textSubtle,
                        disabledBorderColor = Color.Unspecified,
                        disabledContainerColor = c.surfaceAlt,
                        disabledPlaceholderColor = c.textSubtle,
                    )
                },
            modifier = Modifier.fillMaxWidth(),
        )

        BasicText(
            text =
                stringResource(
                    id = R.string.request_memo_bytes_counter,
                    Memo.MAX_MEMO_LENGTH_BYTES - memoState.byteSize,
                    Memo.MAX_MEMO_LENGTH_BYTES,
                ),
            style =
                ZappTheme.typography.body.copy(
                    color = if (memoState.isValid()) c.textSubtle else c.danger,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.4.sp,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
        )

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}
