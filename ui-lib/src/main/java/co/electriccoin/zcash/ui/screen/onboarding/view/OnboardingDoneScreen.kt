package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import kotlinx.coroutines.delay

@Composable
internal fun OnboardingDoneScreen(
    mode: TwoFAMode,
    onEnter: () -> Unit,
) {
    val c = ZappTheme.colors
    val haptic = LocalHapticFeedback.current

    // Staged entrance: check → title → rule → subtitle, one Confirm pulse with the check.
    var stage by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
        while (stage < STAGE_COUNT) {
            stage += 1
            delay(STAGE_DELAY_MS)
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StagedEntrance(visible = stage >= 1, scale = true) {
                    BasicText(
                        text = "✓",
                        style =
                            ZappTheme.typography.display.copy(
                                color = c.accent,
                                fontSize = 88.sp,
                                lineHeight = 92.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-4).sp,
                            ),
                    )
                }
                Spacer(Modifier.height(18.dp))
                StagedEntrance(visible = stage >= 2) {
                    BasicText(
                        text = stringResource(R.string.onboarding_done_title),
                        style =
                            ZappTheme.typography.display.copy(
                                color = c.text,
                                fontSize = 42.sp,
                                lineHeight = 44.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-1.8).sp,
                            ),
                    )
                }
                Spacer(Modifier.height(20.dp))
                StagedEntrance(visible = stage >= 3) {
                    AccentRule()
                }
                Spacer(Modifier.height(20.dp))
                StagedEntrance(visible = stage >= 4) {
                    OnbSub(
                        text =
                            stringResource(
                                when (mode) {
                                    TwoFAMode.Bio -> R.string.onboarding_done_subtitle_bio
                                    TwoFAMode.Pin -> R.string.onboarding_done_subtitle_pin
                                }
                            ),
                    )
                }
            }
        }
        OnbBottomDock(cta = stringResource(R.string.onboarding_done_enter_cta), onCta = onEnter)
    }
}

@Composable
private fun StagedEntrance(
    visible: Boolean,
    scale: Boolean = false,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            if (scale) {
                scaleIn(
                    tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                    initialScale = DONE_CHECK_INITIAL_SCALE,
                ) + fadeIn(tween(ZappMotion.CONTENT_MS))
            } else {
                slideInVertically(
                    tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                ) { it / DONE_SLIDE_DIVISOR } + fadeIn(tween(ZappMotion.CONTENT_MS))
            },
    ) {
        content()
    }
}

private const val STAGE_COUNT = 4
private const val STAGE_DELAY_MS = 90L
private const val DONE_CHECK_INITIAL_SCALE = 0.6f
private const val DONE_SLIDE_DIVISOR = 4
