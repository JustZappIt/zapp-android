@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.authentication.view.ChartPoint
import co.electriccoin.zcash.ui.screen.authentication.view.generateChartPoints
import kotlinx.coroutines.delay

/**
 * Full-screen loading state for seed-persist work (new-wallet encrypt or restore-from-phrase).
 *
 * A brand-yellow Swiss splash: accent background, Black-weight greeting, a 36×3 rule and the
 * jagged-wave motif — but the wave gently breathes on a loop instead of a spinner. If
 * [errorMessage] is set (or the work stalls past [ENCRYPT_TIMEOUT_MS]), the copy is replaced with
 * the error and an optional [onRetry] action.
 */
@Composable
internal fun WalletEncryptingScreen(
    message: String,
    errorMessage: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    retryHint: String = stringResource(R.string.onboarding_seed_loading_retry_hint),
    noRetryHint: String = stringResource(R.string.onboarding_seed_loading_no_retry_hint),
    retryLabel: String = stringResource(R.string.onboarding_seed_loading_retry),
) {
    val c = ZappTheme.colors

    // Fallback so the user is never trapped on an endless animation: if nothing errors but the
    // work also never finishes, surface a generic timeout message.
    var timedOut by remember { mutableStateOf(false) }
    val timeoutMessage = stringResource(R.string.onboarding_seed_loading_timeout)
    LaunchedEffect(errorMessage) {
        timedOut = false
        if (errorMessage == null) {
            delay(ENCRYPT_TIMEOUT_MS)
            timedOut = true
        }
    }
    val displayError = errorMessage ?: timeoutMessage.takeIf { timedOut }

    val transition = rememberInfiniteTransition(label = "encrypting")
    // Single breathing driver, eased and reversing, shared by both wave bands and the dots.
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = WAVE_PERIOD_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulse",
    )
    // Sequential 0→1→2 step used to light the three Swiss loading squares one at a time.
    val dotStep by transition.animateFloat(
        initialValue = 0f,
        targetValue = DOT_COUNT.toFloat(),
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = DOT_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "dotStep",
    )

    // Two independent jagged silhouettes (stable across recompositions) for a subtle parallax.
    val backPoints = remember { generateChartPoints() }
    val frontPoints = remember { generateChartPoints() }

    Box(
        modifier = modifier.fillMaxSize().background(c.accent),
    ) {
        // Breathing wave bands anchored to the bottom edge — the splash motif, kept in motion.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawWaveBand(backPoints, heightFraction = 0.30f + 0.18f * pulse, color = Color.White.copy(alpha = 0.10f))
            drawWaveBand(
                frontPoints,
                heightFraction = 0.20f + 0.16f * (1f - pulse),
                color = Color.White.copy(alpha = 0.16f),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Big Swiss greeting — same Black weight / tight tracking as the "Hi." splash.
            BasicText(
                text = stringResource(R.string.onboarding_encrypting_greeting),
                style =
                    ZappTheme.typography.display.copy(
                        color = Color.White,
                        fontSize = 112.sp,
                        lineHeight = 104.sp,
                        letterSpacing = (-5).sp,
                        fontWeight = FontWeight.Black,
                    ),
            )
            Spacer(Modifier.height(20.dp))
            // 36×3 accent rule — shared device across WelcomeGate and the splash.
            Box(
                modifier =
                    Modifier
                        .width(36.dp)
                        .height(3.dp)
                        .background(c.text, RectangleShape),
            )
            Spacer(Modifier.height(24.dp))

            if (displayError == null) {
                BasicText(
                    text = message,
                    style =
                        ZappTheme.typography.body.copy(
                            color = Color.White,
                            fontSize = 17.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        ),
                    modifier = Modifier.fillMaxWidth(0.9f),
                )
                Spacer(Modifier.height(28.dp))
                // Three sharp-edged squares lighting in sequence — a loading cue, Swiss, no spinner.
                val activeDot = dotStep.toInt().coerceIn(0, DOT_COUNT - 1)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(DOT_COUNT) { index ->
                        Box(
                            modifier =
                                Modifier
                                    .size(10.dp)
                                    .background(
                                        if (index == activeDot) c.text else c.text.copy(alpha = 0.28f),
                                        RectangleShape,
                                    ),
                        )
                    }
                }
            } else {
                BasicText(
                    text = displayError,
                    style =
                        ZappTheme.typography.body.copy(
                            color = c.text,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    modifier = Modifier.fillMaxWidth(0.9f),
                )
                Spacer(Modifier.height(10.dp))
                BasicText(
                    text = if (onRetry != null) retryHint else noRetryHint,
                    style =
                        ZappTheme.typography.body.copy(
                            color = c.text.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        ),
                    modifier = Modifier.fillMaxWidth(0.9f),
                )
                if (onRetry != null) {
                    Spacer(Modifier.height(22.dp))
                    Box(
                        modifier =
                            Modifier
                                .border(width = 2.dp, color = c.text, shape = RectangleShape)
                                .clickable(onClick = onRetry)
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        BasicText(
                            text = retryLabel,
                            style =
                                ZappTheme.typography.body.copy(
                                    color = c.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.2.sp,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** Draws a jagged silhouette rising from the bottom edge, [heightFraction] of the canvas tall. */
private fun DrawScope.drawWaveBand(
    points: List<ChartPoint>,
    heightFraction: Float,
    color: Color,
) {
    val band = size.height * heightFraction
    val baseline = size.height
    val path =
        Path().apply {
            moveTo(0f, baseline)
            for (i in points.indices) {
                lineTo(size.width * points[i].x, baseline - band * points[i].y)
            }
            lineTo(size.width, baseline)
            close()
        }
    drawPath(path, color = color, style = Fill)
}

private const val ENCRYPT_TIMEOUT_MS = 15_000L
private const val WAVE_PERIOD_MS = 2800
private const val DOT_COUNT = 3
private const val DOT_PERIOD_MS = 1200
