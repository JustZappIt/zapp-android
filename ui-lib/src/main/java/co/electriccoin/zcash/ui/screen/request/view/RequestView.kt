@file:Suppress("TooManyFunctions")

package co.electriccoin.zcash.ui.screen.request.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.sdk.type.ZcashCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.QrCodeDefaults
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.request.model.AmountState
import co.electriccoin.zcash.ui.screen.request.model.MemoState
import co.electriccoin.zcash.ui.screen.request.model.QrCodeState
import co.electriccoin.zcash.ui.screen.request.model.Request
import co.electriccoin.zcash.ui.screen.request.model.RequestCurrency
import co.electriccoin.zcash.ui.screen.request.model.RequestState
import kotlin.math.roundToInt

@Composable
@PreviewScreens
private fun RequestLoadingPreview() =
    ZcashTheme(forceDarkMode = true) {
        RequestView(
            state = RequestState.Loading,
            snackbarHostState = SnackbarHostState(),
        )
    }

@Composable
@PreviewScreens
private fun RequestPreview() =
    ZcashTheme(forceDarkMode = false) {
        RequestView(
            state =
                RequestState.Amount(
                    request =
                        Request(
                            amountState = AmountState("2.25", RequestCurrency.ZEC, true),
                            memoState = MemoState.Valid("", 0, "2.25"),
                            qrCodeState =
                                QrCodeState(
                                    "zcash:t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi?amount=1&memo=VGhpcyBpcyBhIHNpbXBsZSBt",
                                    "0.25",
                                    memo = "Text memo",
                                ),
                        ),
                    exchangeRateState = ExchangeRateState.OptedOut,
                    zcashCurrency = ZcashCurrency.ZEC,
                    onAmount = {},
                    onSwitch = {},
                    onBack = {},
                    onMemo = {},
                ) {},
            snackbarHostState = SnackbarHostState(),
        )
    }

@Composable
internal fun RequestView(
    state: RequestState,
    snackbarHostState: SnackbarHostState,
) {
    when (state) {
        RequestState.Loading -> {
            CircularScreenProgressIndicator()
        }

        is RequestState.Prepared -> {
            val c = ZappTheme.colors
            Scaffold(
                topBar = {
                    ZappScreenHeader(
                        title = stringResource(id = R.string.request_title),
                        containerColor = c.bg,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    RequestBottomBar(state = state)
                },
                containerColor = c.bg,
                contentWindowInsets = WindowInsets(0),
            ) { paddingValues ->
                RequestContents(
                    state = state,
                    modifier =
                        Modifier.padding(
                            top = paddingValues.calculateTopPadding(),
                            bottom = paddingValues.calculateBottomPadding()
                        ),
                )
            }
        }
    }
}

@Composable
private fun RequestBottomBar(
    state: RequestState.Prepared,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 12.dp)
                .border(BorderStroke(1.dp, c.text), RectangleShape),
    ) {
        when (state) {
            is RequestState.Amount -> {
                SwissDockRow(
                    onBack = state.onBack,
                    cta = stringResource(id = R.string.request_amount_btn),
                    onCta = state.onDone,
                    ctaEnabled = state.request.amountState.isValid == true,
                )
            }

            is RequestState.Memo -> {
                SwissDockRow(
                    onBack = state.onBack,
                    cta = stringResource(id = R.string.request_memo_btn),
                    onCta = state.onDone,
                    ctaEnabled = state.request.memoState.isValid(),
                )
            }

            is RequestState.QrCode -> {
                val sizePixels = with(LocalDensity.current) { DEFAULT_QR_CODE_SIZE.toPx() }.roundToInt()
                val colors = QrCodeDefaults.colors()
                SwissDockRow(
                    onBack = state.onBack,
                    cta = stringResource(id = R.string.request_qr_share_btn),
                    onCta = { state.onQrCodeShare(colors, sizePixels, state.request.qrCodeState.requestUri) },
                    ctaEnabled = true,
                )
            }
        }
    }
}

@Composable
private fun SwissDockRow(
    onBack: () -> Unit,
    cta: String,
    onCta: () -> Unit,
    ctaEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwissBackBox(onClick = onBack)
        SwissPrimaryButton(
            text = cta,
            onClick = onCta,
            enabled = ctaEnabled,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SwissBackBox(onClick: () -> Unit) {
    val c = ZappTheme.colors
    val backContentDescription = stringResource(R.string.request_back_content_description)
    Box(
        modifier =
            Modifier
                .size(width = 72.dp, height = 52.dp)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = backContentDescription
                    role = Role.Button
                },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = stringResource(R.string.request_back_glyph),
            style =
                ZappTheme.typography.button.copy(
                    color = c.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                ),
        )
    }
}

@Composable
private fun SwissPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val bg = if (enabled) c.accent else c.surfaceAlt
    val fg = if (enabled) c.onAccent else c.textSubtle
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(bg, RectangleShape)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text.uppercase(),
            style =
                ZappTheme.typography.button.copy(
                    color = fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.6.sp,
                ),
        )
    }
}

val DEFAULT_QR_CODE_SIZE = 320.dp

@Composable
private fun RequestContents(
    state: RequestState.Prepared,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        when (state) {
            is RequestState.Amount -> {
                RequestAmountView(state = state)
            }

            is RequestState.Memo -> {
                RequestMemoView(state = state)
            }

            is RequestState.QrCode -> {
                RequestQrCodeView(state = state)
            }
        }
    }
}

// TODO [#1635]: Learn AutoSizingText scale up
// TODO [#1635]: https://github.com/Electric-Coin-Company/zashi-android/issues/1635
@Composable
internal fun AutoSizingText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    var fontSize by remember { mutableStateOf(style.fontSize) }

    Text(
        text = text,
        fontSize = fontSize,
        fontFamily = style.fontFamily,
        lineHeight = style.lineHeight,
        fontWeight = style.fontWeight,
        maxLines = 1,
        modifier = modifier,
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.didOverflowHeight) {
                fontSize = (fontSize.value - 1).sp
            } else {
                // We should make the text bigger again
            }
        }
    )
}
