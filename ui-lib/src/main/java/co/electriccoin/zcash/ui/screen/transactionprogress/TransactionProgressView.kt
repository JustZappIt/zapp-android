@file:Suppress("TooManyFunctions")

package co.electriccoin.zcash.ui.screen.transactionprogress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.loadingImageRes
import co.electriccoin.zcash.ui.design.util.orDark
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressState.Background.ERROR
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressState.Background.PENDING
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressState.Background.SUCCESS
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

@Composable
fun TransactionProgressView(state: TransactionProgressState) {
    val c = ZappTheme.colors
    val haptic = LocalHapticFeedback.current
    // The payment landing is the emotional peak of the flow — punctuate it.
    LaunchedEffect(state.background) {
        when (state.background) {
            SUCCESS -> runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
            ERROR -> runCatching { haptic.performHapticFeedback(HapticFeedbackType.Reject) }
            else -> Unit
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .imePadding(),
    ) {
        TopBar(state)

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (state.transactionIds == null) 80.dp else 24.dp))
            ImageOrLoading(state.image)
            Spacer(Modifier.height(28.dp))
            TitleBlock(state)
            if (state.transactionIds != null) {
                Spacer(Modifier.height(36.dp))
                TransactionIdList(state.transactionIds)
            }
            Spacer(Modifier.height(28.dp))
            if (state.middleButton != null) {
                ZappButton(
                    text = state.middleButton.text.getValue(),
                    variant = ZappButtonVariant.Ghost,
                    onClick = state.middleButton.onClick,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        BottomBar(state)
    }
}

@Composable
private fun TopBar(state: TransactionProgressState) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.showAppBar) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .border(BorderStroke(1.dp, c.border), RectangleShape)
                        .clickable(onClick = state.onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_small),
                    contentDescription = null,
                    tint = c.text,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TitleBlock(state: TransactionProgressState) {
    val c = ZappTheme.colors
    val accent =
        when (state.background) {
            SUCCESS -> c.success
            ERROR -> c.danger
            PENDING, null -> c.accent
        }

    // Tiny eyebrow indicator above the title — mirrors the Swiss "section label" style
    val eyebrowText =
        when (state.background) {
            SUCCESS -> "RESULT"
            ERROR -> "RESULT"
            PENDING -> "STATUS"
            null -> null
        }
    if (eyebrowText != null) {
        BasicText(
            text = eyebrowText,
            style = ZappTheme.typography.eyebrow.copy(color = accent),
        )
        Spacer(Modifier.height(10.dp))
    }

    BasicText(
        text = state.title.getValue(),
        style = ZappTheme.typography.display.copy(color = c.text),
    )
    Spacer(Modifier.height(12.dp))
    BasicText(
        text = state.subtitle.getValue(),
        style =
            ZappTheme.typography.body.copy(
                color = c.textMuted,
                textAlign = TextAlign.Center,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TransactionIdList(ids: List<co.electriccoin.zcash.ui.design.util.StringResource>) {
    val c = ZappTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        BasicText(
            text = stringResource(id = R.string.send_confirmation_multiple_trx_failure_ids_title).uppercase(),
            style = ZappTheme.typography.eyebrow.copy(color = c.textSubtle),
        )
        Spacer(Modifier.height(10.dp))
        ids.forEachIndexed { index, item ->
            if (index != 0) Spacer(Modifier.height(8.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(c.surfaceAlt, RectangleShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                BasicText(
                    text = item.getValue(),
                    style = ZappTheme.typography.mono.copy(color = c.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BottomBar(state: TransactionProgressState) {
    if (state.secondaryButton == null && state.primaryButton == null) return
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.secondaryButton?.let { btn ->
            ZappButton(
                text = btn.text.getValue(),
                modifier = Modifier.fillMaxWidth(),
                variant = ZappButtonVariant.Ghost,
                onClick = btn.onClick,
            )
        }
        state.primaryButton?.let { btn ->
            ZappButton(
                text = btn.text.getValue(),
                modifier = Modifier.fillMaxWidth(),
                variant = ZappButtonVariant.Primary,
                onClick = btn.onClick,
            )
        }
    }
}

@Composable
private fun ImageOrLoading(imageResource: ImageResource) {
    when (imageResource) {
        is ImageResource.ByDrawable -> {
            Image(
                painter = painterResource(imageResource.resource),
                contentDescription = null,
                modifier = Modifier.size(120.dp),
            )
        }

        ImageResource.Loading -> {
            val lottieRes = R.raw.send_confirmation_sending_v1 orDark R.raw.send_confirmation_sending_dark_v1
            val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(lottieRes))
            val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)

            LottieAnimation(
                modifier =
                    Modifier
                        .size(150.dp)
                        .graphicsLayer {
                            scaleX = LOTTIE_ANIM_SCALE
                            scaleY = LOTTIE_ANIM_SCALE
                        },
                composition = composition,
                progress = { progress },
                maintainOriginalImageBounds = true,
            )
        }

        is ImageResource.DisplayString -> {
            // do nothing
        }
    }
}

private const val LOTTIE_ANIM_SCALE = 1.54f

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        TransactionProgressView(
            state =
                TransactionProgressState(
                    title = stringRes("Sent!"),
                    subtitle = stringRes("Your coins were successfully sent to utest1abc…xyz").withStyle(),
                    middleButton =
                        ButtonState(
                            text = stringRes("View details"),
                            onClick = { }
                        ),
                    secondaryButton =
                        ButtonState(
                            text = stringRes("Close"),
                            onClick = {},
                            style = ButtonStyle.SECONDARY
                        ),
                    primaryButton =
                        ButtonState(
                            text = stringRes("Done"),
                            onClick = {},
                            style = ButtonStyle.PRIMARY
                        ),
                    onBack = {},
                    background = SUCCESS,
                    image = imageRes(R.drawable.ic_face_star),
                    transactionIds =
                        listOf(
                            stringRes("0xabc1234567890abcdef1234567890abcdef1234567890abcdef"),
                        ),
                    showAppBar = true
                )
        )
    }

@PreviewScreens
@Composable
private fun SendingPreview() =
    ZcashTheme {
        TransactionProgressView(
            state =
                TransactionProgressState(
                    title = stringRes("Sending…"),
                    subtitle = stringRes("Your coins are being sent to utest1abc…xyz").withStyle(),
                    middleButton = null,
                    secondaryButton = null,
                    primaryButton = null,
                    onBack = {},
                    background = PENDING,
                    image = loadingImageRes(),
                )
        )
    }
