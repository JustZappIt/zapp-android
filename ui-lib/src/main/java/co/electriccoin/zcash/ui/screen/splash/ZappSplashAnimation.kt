@file:Suppress("MatchingDeclarationName", "DEPRECATION")

package co.electriccoin.zcash.ui.screen.splash

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.splash.ZappSplashConstants.CLEAR_SAFETY
import co.electriccoin.zcash.ui.screen.splash.ZappSplashConstants.HOLD_MS
import co.electriccoin.zcash.ui.screen.splash.ZappSplashConstants.MARK_SCALE
import co.electriccoin.zcash.ui.screen.splash.ZappSplashConstants.OPTICAL_CENTERING_X
import co.electriccoin.zcash.ui.screen.splash.ZappSplashConstants.SLIDE_IN_MS
import co.electriccoin.zcash.ui.screen.splash.ZappSplashConstants.SLIDE_OUT_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

const val ZAPP_SPLASH_TEST_TAG = "ZAPP_SPLASH_TEST_TAG"

/**
 * Tune these to match the Figma transition panel. Measured from zappSplash.mov:
 * slide-in ~450ms, hold ~500ms, slide-out ~700ms (eased); locked to a symmetric
 * ease-in-out per design sign-off. The slide-in runs noticeably longer than the other
 * phases and uses a symmetric [EaseInOut] (not fast-out) so the halves ramp in gently
 * instead of snapping to speed the instant they appear.
 */
object ZappSplashConstants {
    const val SLIDE_IN_MS = 1000
    const val HOLD_MS = 500
    const val SLIDE_OUT_MS = 500

    // Overshoot past the minimal off-screen distance so each half fully clears the viewport.
    const val CLEAR_SAFETY = 1.1f

    // The exported mark sits slightly left of its optical centre. This correction centres the
    // complete Z rather than its 424dp export frame.
    val OPTICAL_CENTERING_X = 2.dp

    // A small scale-up gives the mark a little more presence without affecting the handoff.
    const val MARK_SCALE = 1.02f
}

// Mirror ZcashTheme's navigation-bar contrast scrims so the splash can drop them while it covers
// the screen and restore the exact same styling when it leaves.
private const val NAV_LIGHT_ALPHA = 0xe6
private const val NAV_LIGHT_CHANNEL = 0xFF
private const val NAV_DARK_ALPHA = 0x80
private const val NAV_DARK_CHANNEL = 0x1b
private val NAV_LIGHT_SCRIM =
    AndroidColor.argb(NAV_LIGHT_ALPHA, NAV_LIGHT_CHANNEL, NAV_LIGHT_CHANNEL, NAV_LIGHT_CHANNEL)
private val NAV_DARK_SCRIM =
    AndroidColor.argb(NAV_DARK_ALPHA, NAV_DARK_CHANNEL, NAV_DARK_CHANNEL, NAV_DARK_CHANNEL)

/**
 * Brand splash. The Z is two orange triangles (R.drawable.zapp_z_top / _bottom, exported from
 * Figma) split along the Z's main diagonal. A single progress value drives both halves
 * symmetrically: the top-left half and bottom-right half slide in from opposite corners to meet
 * and form the Z (progress 1 → 0), hold, then part back to those corners (0 → 1), revealing the
 * white app underneath. Both halves translate perpendicular to the diagonal, so the white seam
 * stays parallel to it.
 *
 * [start] gates the motion: while false the splash is a static background field (halves
 * off-screen), so it covers the app until the system splash has actually exited. After sliding
 * in and holding, the assembled Z remains behind app-access authentication until [canFinish] is
 * true. It then parts to reveal authenticated app content.
 */
@Composable
internal fun ZappSplashAnimation(
    start: Boolean,
    canFinish: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // While the splash covers the screen, drop the navigation-bar contrast scrim so the orange Z
    // reaches the very bottom edge (behind the gesture pill). Deferred one frame so it lands AFTER
    // ZcashTheme's edge-to-edge setup — whose LaunchedEffect re-enables the scrim — then restored
    // when the splash leaves.
    val activity = LocalActivity.current as? ComponentActivity
    val darkMode = isSystemInDarkTheme()
    LaunchedEffect(activity) {
        withFrameNanos { }
        val window = activity?.window ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.navigationBarColor = AndroidColor.TRANSPARENT
    }
    DisposableEffect(activity, darkMode) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = true
                window.navigationBarColor = AndroidColor.TRANSPARENT
            } else {
                window.navigationBarColor = if (darkMode) NAV_DARK_SCRIM else NAV_LIGHT_SCRIM
            }
        }
    }

    // 1f = parted (off-screen), 0f = centered (full Z).
    val progress = remember { Animatable(1f) }
    val finishAllowed = rememberUpdatedState(canFinish)

    // Backdrop behind the two halves. While the Z forms and holds it is the brand field
    // colour, so the very first cold-start frame is already brand-orange rather than the
    // white app backdrop — no white flash before the slide-in. It flips to the app backdrop
    // the instant the slide-out starts (when the halves fully cover the screen, so the swap
    // is invisible), leaving the parting reveal onto white exactly as before.
    val slidingOut = remember { mutableStateOf(false) }
    val backdrop =
        colorResource(if (slidingOut.value) R.color.zapp_splash_bg else R.color.zapp_splash_field)

    LaunchedEffect(start) {
        if (!start) return@LaunchedEffect
        progress.animateTo(0f, tween(durationMillis = SLIDE_IN_MS, easing = EaseInOut))
        delay(HOLD_MS.toLong())
        snapshotFlow { finishAllowed.value }.first { it }
        slidingOut.value = true
        progress.animateTo(1f, tween(durationMillis = SLIDE_OUT_MS, easing = FastOutSlowInEasing))
        onFinished()
    }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(backdrop)
                .testTag(ZAPP_SPLASH_TEST_TAG),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val opticalCenteringX = with(LocalDensity.current) { OPTICAL_CENTERING_X.toPx() }

        // Unit vector perpendicular to the top-right→bottom-left diagonal, pointing bottom-right,
        // scaled to the distance that just clears a half off the corner (× safety overshoot).
        val diagSq = w * w + h * h
        val partX = CLEAR_SAFETY * w * h * h / diagSq
        val partY = CLEAR_SAFETY * w * w * h / diagSq

        Image(
            painter = painterResource(R.drawable.zapp_z_top),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.FillBounds,
            modifier =
                Modifier
                    .fillMaxSize()
                    .scale(MARK_SCALE)
                    .offset {
                        IntOffset(
                            x = (opticalCenteringX - progress.value * partX).roundToInt(),
                            y = (-progress.value * partY).roundToInt(),
                        )
                    },
        )
        Image(
            painter = painterResource(R.drawable.zapp_z_bottom),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier =
                Modifier
                    .fillMaxSize()
                    .scale(MARK_SCALE)
                    .offset {
                        IntOffset(
                            x = (opticalCenteringX + progress.value * partX).roundToInt(),
                            y = (progress.value * partY).roundToInt(),
                        )
                    },
        )
    }
}
