package co.electriccoin.zcash.ui.screen.onboarding.view

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
import androidx.compose.foundation.layout.navigationBars
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

/**
 * Single-phase PIN entry for authentication verification.
 *
 * Submits the PIN automatically once 6 digits are entered. Clears the input
 * immediately on submission. When [hasError] transitions to true (wrong PIN),
 * the input is reset and an error indicator is shown so the user can retry.
 *
 * @param hasError True while the most-recent submission failed; drives the error
 *   dot colour and clears the input.
 * @param showBack Whether to show a back / cancel button at the bottom. Pass
 *   `false` for mandatory auth gates (e.g. app-open) where the user cannot skip.
 * @param onPinSubmit Called with the 6-digit string once the user completes entry.
 * @param onCancel Called when the user cancels (navigates back). Only relevant
 *   when [showBack] is true.
 */
@Composable
internal fun PinVerifyScreen(
    hasError: Boolean,
    showBack: Boolean = true,
    lockoutSecondsRemaining: Int = 0,
    onPinSubmit: (String) -> Unit,
    onCancel: () -> Unit = {},
) {
    SecureScreen()

    val c = ZappTheme.colors
    val haptic = LocalHapticFeedback.current
    var currentInput by rememberSaveable { mutableStateOf("") }
    val isLocked = lockoutSecondsRemaining > 0

    // Clear input whenever an error or lockout is signalled so the user starts fresh.
    LaunchedEffect(hasError, isLocked) {
        if (hasError || isLocked) {
            currentInput = ""
            runCatching { haptic.performHapticFeedback(HapticFeedbackType.Reject) }
        }
    }

    // Auto-submit when 6 digits are entered. Skipped while locked so the keypad
    // is effectively disabled.
    LaunchedEffect(currentInput) {
        if (!isLocked && currentInput.length == 6) {
            val pin = currentInput
            currentInput = ""
            onPinSubmit(pin)
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
                    .padding(start = 28.dp, end = 28.dp, top = 24.dp),
        ) {
            Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                Spacer(Modifier.height(14.dp))
                OnbHero(text = stringResource(R.string.onboarding_pin_verify_title))
                Spacer(Modifier.height(14.dp))
                val messageKind =
                    when {
                        isLocked -> PinMessageKind.LOCKED
                        hasError -> PinMessageKind.ERROR
                        else -> PinMessageKind.IDLE
                    }
                AnimatedContent(
                    targetState = messageKind,
                    transitionSpec = {
                        (
                            fadeIn(tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing)) +
                                slideInVertically(
                                    tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                                ) { it / PIN_VERIFY_MESSAGE_SLIDE_DIVISOR }
                        ).togetherWith(fadeOut(tween(ZappMotion.STATE_MS)))
                    },
                    label = "pinVerifyMessage",
                ) { kind ->
                    when (kind) {
                        PinMessageKind.LOCKED -> {
                            BasicText(
                                text =
                                    stringResource(
                                        R.string.onboarding_pin_verify_lockout,
                                        lockoutSecondsRemaining,
                                    ),
                                style =
                                    ZappTheme.typography.body.copy(
                                        color = c.danger,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                    ),
                            )
                        }

                        PinMessageKind.ERROR -> {
                            BasicText(
                                text = stringResource(R.string.onboarding_pin_verify_incorrect),
                                style =
                                    ZappTheme.typography.body.copy(
                                        color = c.danger,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                    ),
                            )
                        }

                        PinMessageKind.IDLE -> {
                            OnbSub(text = stringResource(R.string.onboarding_pin_verify_subtitle))
                        }
                    }
                }
            }
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .let { if (!showBack) it.windowInsetsPadding(WindowInsets.navigationBars) else it }
                        .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PinDotRow(
                    filledCount = currentInput.length,
                    hasError = hasError || isLocked,
                    modifier = Modifier.shake(hasError || isLocked),
                )
                Spacer(Modifier.height(28.dp))
                PinKeypad(
                    modifier = Modifier.fillMaxWidth(),
                    onKey = { key ->
                        if (isLocked) return@PinKeypad
                        when {
                            key == "⌫" -> if (currentInput.isNotEmpty()) currentInput = currentInput.dropLast(1)
                            currentInput.length < 6 -> currentInput += key
                        }
                    },
                )
            }
        }
        if (showBack) {
            OnbBottomDock(
                cta = "",
                onCta = {},
                showBack = true,
                onBack = onCancel,
                showCta = false,
            )
        }
    }
}

private enum class PinMessageKind { IDLE, ERROR, LOCKED }

private const val PIN_VERIFY_MESSAGE_SLIDE_DIVISOR = 4
