package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.animation.shake
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import kotlinx.coroutines.delay

/**
 * Two-phase 6-digit PIN creation screen.
 *
 * Phase 1 — user enters a new PIN.
 * Phase 2 — user re-enters the same PIN to confirm.
 * Mismatch: error shown for 1.5 s, then resets to phase 1.
 * On match: [onPinConfirmed] is called with the raw 6-digit string.
 */
@Composable
internal fun PinSetupScreen(
    onBack: () -> Unit,
    onPinConfirmed: (String) -> Unit,
) {
    SecureScreen()

    val c = ZappTheme.colors
    val haptic = LocalHapticFeedback.current

    var isConfirmPhase by rememberSaveable { mutableStateOf(false) }
    var firstPin by rememberSaveable { mutableStateOf("") }
    var currentInput by rememberSaveable { mutableStateOf("") }
    var mismatchError by rememberSaveable { mutableStateOf(false) }

    val handleBack = {
        if (isConfirmPhase) {
            isConfirmPhase = false
            currentInput = ""
            firstPin = ""
        } else {
            onBack()
        }
    }

    // This screen owns a nested two-phase state machine. Register after the parent
    // onboarding handler so system Back first leaves confirmation, exactly like the dock.
    BackHandler(onBack = handleBack)

    // Auto-advance phases when 6 digits are entered.
    LaunchedEffect(currentInput) {
        if (currentInput.length == 6) {
            if (!isConfirmPhase) {
                firstPin = currentInput
                currentInput = ""
                isConfirmPhase = true
            } else {
                if (currentInput == firstPin) {
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                    onPinConfirmed(currentInput)
                } else {
                    mismatchError = true
                    currentInput = ""
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.Reject) }
                }
            }
        }
    }

    // Auto-reset after mismatch so the user starts over from phase 1.
    LaunchedEffect(mismatchError) {
        if (mismatchError) {
            delay(MISMATCH_RESET_DELAY_MS)
            isConfirmPhase = false
            firstPin = ""
            currentInput = ""
            mismatchError = false
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 20.dp)) {
            OnbProgress(step = 3)
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp, top = 24.dp),
        ) {
            GhostNum(n = 3, modifier = Modifier.align(Alignment.TopEnd))
            Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                Eyebrow(stringResource(R.string.onboarding_secure_badge))
                Spacer(Modifier.height(14.dp))
                AnimatedContent(
                    targetState = isConfirmPhase,
                    transitionSpec = {
                        (
                            fadeIn(tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing)) +
                                slideInVertically(
                                    tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                                ) { it / PIN_SETUP_MESSAGE_SLIDE_DIVISOR }
                        ).togetherWith(fadeOut(tween(ZappMotion.STATE_MS)))
                    },
                    label = "pinSetupHero",
                ) { confirmPhase ->
                    OnbHero(
                        text =
                            stringResource(
                                if (confirmPhase) {
                                    R.string.security_settings_change_pin_confirm_title
                                } else {
                                    R.string.onboarding_pin_create_title
                                }
                            )
                    )
                }
                Spacer(Modifier.height(14.dp))
                AnimatedContent(
                    targetState = mismatchError,
                    transitionSpec = {
                        (
                            fadeIn(tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing)) +
                                slideInVertically(
                                    tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                                ) { it / PIN_SETUP_MESSAGE_SLIDE_DIVISOR }
                        ).togetherWith(fadeOut(tween(ZappMotion.STATE_MS)))
                    },
                    label = "pinSetupMessage",
                ) { showMismatch ->
                    if (showMismatch) {
                        BasicText(
                            text = stringResource(R.string.onboarding_pin_mismatch),
                            style =
                                ZappTheme.typography.body.copy(
                                    color = c.danger,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                ),
                        )
                    } else {
                        OnbSub(
                            text =
                                stringResource(
                                    if (isConfirmPhase) {
                                        R.string.onboarding_pin_confirm_subtitle
                                    } else {
                                        R.string.onboarding_pin_create_subtitle
                                    }
                                ),
                        )
                    }
                }
            }
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PinDotRow(
                    filledCount = currentInput.length,
                    hasError = mismatchError,
                    modifier = Modifier.shake(mismatchError),
                )
                Spacer(Modifier.height(28.dp))
                PinKeypad(
                    modifier = Modifier.fillMaxWidth(),
                    onKey = { key ->
                        if (!mismatchError) {
                            when {
                                key == "⌫" -> {
                                    if (currentInput.isNotEmpty()) {
                                        currentInput = currentInput.dropLast(1)
                                    }
                                }

                                currentInput.length < 6 -> {
                                    currentInput += key
                                }
                            }
                        }
                    },
                )
            }
        }
        OnbBottomDock(
            cta = "",
            onCta = {},
            showBack = true,
            onBack = handleBack,
            showCta = false,
        )
    }
}

private const val MISMATCH_RESET_DELAY_MS = 1_500L
private const val PIN_SETUP_MESSAGE_SLIDE_DIVISOR = 4
