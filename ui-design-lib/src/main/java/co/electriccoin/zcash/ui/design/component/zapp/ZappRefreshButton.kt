package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import kotlin.math.ceil

/**
 * Spins while [isRefreshing], then finishes its turn. A tap while it spins does nothing, and a screen
 * reader hears [refreshingDescription] rather than a disabled button.
 */
@Composable
fun ZappRefreshButton(
    isRefreshing: Boolean,
    contentDescription: String,
    refreshingDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isRefreshing) {
        while (isRefreshing) {
            rotation.animateTo(rotation.value + FULL_TURN, tween(TURN_MS, easing = LinearEasing))
        }
        rotation.animateTo(
            ceil(rotation.value / FULL_TURN) * FULL_TURN,
            tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
        )
    }
    ZappIconButton(
        icon = Icons.Default.Refresh,
        contentDescription = contentDescription,
        onClick = { if (!isRefreshing) onClick() },
        modifier = modifier.semantics { if (isRefreshing) stateDescription = refreshingDescription },
        tint = if (isRefreshing) ZappTheme.colors.textMuted else ZappTheme.colors.text,
        iconModifier = Modifier.graphicsLayer { rotationZ = rotation.value },
    )
}

private const val FULL_TURN = 360f
private const val TURN_MS = 900
