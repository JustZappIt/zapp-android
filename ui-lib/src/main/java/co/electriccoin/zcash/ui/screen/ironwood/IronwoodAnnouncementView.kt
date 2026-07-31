package co.electriccoin.zcash.ui.screen.ironwood

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun IronwoodAnnouncementView(state: IronwoodAnnouncementState) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 28.dp, end = 28.dp, top = 24.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_ironwood_zapp_logos),
                contentDescription = null,
            )

            Spacer(Modifier.height(24.dp))

            BasicText(
                text = stringResource(R.string.ironwood_announcement_eyebrow).uppercase(),
                style =
                    ZappTheme.typography.eyebrow.copy(
                        color = c.accent,
                        fontSize = 10.sp,
                        letterSpacing = 2.5.sp,
                        fontWeight = FontWeight.Black,
                    ),
            )

            Spacer(Modifier.height(14.dp))

            BasicText(
                text = stringResource(R.string.ironwood_announcement_title),
                style =
                    ZappTheme.typography.display.copy(
                        color = c.text,
                        fontSize = 34.sp,
                        lineHeight = 38.sp,
                        letterSpacing = (-1.2).sp,
                        fontWeight = FontWeight.Black,
                    ),
            )

            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.width(36.dp).height(3.dp).background(c.text, RectangleShape))

            Spacer(Modifier.height(20.dp))

            Body(stringResource(R.string.ironwood_announcement_body_1))
            Spacer(Modifier.height(14.dp))
            Body(stringResource(R.string.ironwood_announcement_body_2))
            Spacer(Modifier.height(14.dp))
            Body(stringResource(R.string.ironwood_announcement_body_3))

            Spacer(Modifier.height(24.dp))

            GuideParagraph(onGuideClick = state.onGuideClick)

            Spacer(Modifier.height(24.dp))
        }

        PrimaryDock(state.primaryButton)
    }
}

/** Body copy wraps at 94% width, matching the onboarding subtitle measure. */
private const val BODY_WIDTH_FRACTION = 0.94f

@Composable
private fun Body(text: String) {
    val c = ZappTheme.colors
    BasicText(
        text = text,
        modifier = Modifier.fillMaxWidth(BODY_WIDTH_FRACTION),
        style = ZappTheme.typography.body.copy(color = c.textMuted, fontSize = 13.sp, lineHeight = 22.sp),
    )
}

@Composable
private fun GuideParagraph(onGuideClick: () -> Unit) {
    val c = ZappTheme.colors
    val full = stringResource(R.string.ironwood_announcement_body_guide)
    val linkText = stringResource(R.string.ironwood_announcement_body_guide_link)
    val start = full.indexOf(linkText)

    val annotated =
        buildAnnotatedString {
            append(full)
            if (start >= 0 && linkText.isNotEmpty()) {
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "guide",
                        styles =
                            TextLinkStyles(
                                style =
                                    SpanStyle(
                                        color = c.accent,
                                        fontWeight = FontWeight.Black,
                                        textDecoration = TextDecoration.Underline,
                                    )
                            ),
                    ) { onGuideClick() },
                    start = start,
                    end = start + linkText.length,
                )
            }
        }

    BasicText(
        text = annotated,
        style = ZappTheme.typography.body.copy(color = c.text, fontSize = 13.sp, lineHeight = 22.sp),
    )
}

@Composable
private fun PrimaryDock(button: ButtonState) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp)
                .padding(bottom = 8.dp),
    ) {
        ZappButton(
            text = button.text.getValue(),
            modifier = Modifier.fillMaxWidth(),
            enabled = button.isEnabled && !button.isLoading,
            onClick = button.onClick,
        )
    }
}

@PreviewScreens
@Composable
private fun IronwoodAnnouncementPreview() =
    ZcashTheme {
        ProvideZappTheme {
            IronwoodAnnouncementView(
                state =
                    IronwoodAnnouncementState(
                        onGuideClick = {},
                        primaryButton =
                            ButtonState(
                                text = stringRes(R.string.ironwood_announcement_primary_button),
                            ),
                    ),
            )
        }
    }
