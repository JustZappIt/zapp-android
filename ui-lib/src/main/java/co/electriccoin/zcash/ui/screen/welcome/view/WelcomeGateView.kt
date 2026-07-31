package co.electriccoin.zcash.ui.screen.welcome.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme

@Composable
internal fun WelcomeGateView(
    onGetStarted: () -> Unit,
    onRestoreExisting: () -> Unit,
) {
    ProvideZappTheme {
        WelcomeGateContent(onGetStarted = onGetStarted, onRestoreExisting = onRestoreExisting)
    }
}

@Composable
private fun WelcomeGateContent(
    onGetStarted: () -> Unit,
    onRestoreExisting: () -> Unit,
) {
    val c = ZappTheme.colors
    val safeBottomPadding = WindowInsets.safeContent.asPaddingValues().calculateBottomPadding()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // ── Hero block ───────────────────────────────────────────
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp, top = 36.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // Logo + wordmark
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .background(c.accent, RectangleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(R.string.welcome_logo_glyph),
                        style =
                            ZappTheme.typography.display.copy(
                                color = c.onAccent,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                            ),
                    )
                }
                Spacer(Modifier.width(12.dp))
                BasicText(
                    text = stringResource(R.string.welcome_wordmark),
                    style =
                        ZappTheme.typography.screenTitle.copy(
                            color = c.text,
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp,
                        ),
                )
            }

            Spacer(Modifier.height(40.dp))

            // Hero — line 1 default, line 2 accent
            BasicText(
                text = stringResource(R.string.welcome_hero_line1),
                style =
                    ZappTheme.typography.display.copy(
                        color = c.text,
                        fontWeight = FontWeight.Black,
                        fontSize = 54.sp,
                        lineHeight = 52.sp,
                        letterSpacing = (-2.4).sp,
                    ),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = stringResource(R.string.welcome_hero_line2),
                style =
                    ZappTheme.typography.display.copy(
                        color = c.accent,
                        fontWeight = FontWeight.Black,
                        fontSize = 54.sp,
                        lineHeight = 52.sp,
                        letterSpacing = (-2.4).sp,
                    ),
            )

            Spacer(Modifier.height(24.dp))
            // 36×3 accent rule
            Box(
                modifier =
                    Modifier
                        .size(width = 36.dp, height = 3.dp)
                        .background(c.text, RectangleShape),
            )
            Spacer(Modifier.height(20.dp))

            BasicText(
                text = stringResource(R.string.welcome_body),
                style =
                    ZappTheme.typography.body.copy(
                        color = c.textMuted,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                    ),
                modifier = Modifier.fillMaxWidth(0.85f),
            )
        }

        // ── Footer / CTAs ─────────────────────────────────────────
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Keep the footer's outline and actions clear of system bars and system
                    // gestures. This padding must be outside the border; otherwise the bottom
                    // rule is drawn beneath the system navigation UI.
                    .padding(bottom = safeBottomPadding)
                    .border(width = 1.dp, color = c.text, shape = RectangleShape)
                    .padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 24.dp),
        ) {
            ZappButton(
                text = stringResource(R.string.welcome_get_started_button),
                onClick = onGetStarted,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp),
            )
            Spacer(Modifier.height(8.dp))
            ZappButton(
                text = stringResource(R.string.welcome_restore_button),
                onClick = onRestoreExisting,
                variant = ZappButtonVariant.Ghost,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.welcome_terms_caption),
                style =
                    ZappTheme.typography.caption.copy(
                        color = c.textSubtle,
                        fontSize = 10.sp,
                        letterSpacing = 0.3.sp,
                        textAlign = TextAlign.Center,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
